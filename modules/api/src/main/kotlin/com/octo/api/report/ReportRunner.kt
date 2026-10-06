package com.octo.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.octo.analytics.CashFlow
import com.octo.analytics.CashFlowSeries
import com.octo.analytics.SectorPerformance
import com.octo.analytics.brinson
import com.octo.analytics.performance
import com.octo.api.agents.AgentsClient
import com.octo.iborcore.FlowType
import com.octo.iborcore.LedgerEvent
import com.octo.iborcore.glJournal
import com.octo.workflow.report.ReportJob
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.scheduling.annotation.Scheduled
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs queued report jobs in process (#105: a poller, not a queue). Each poll drains the queue one claim at a
 * time; a job's outcome is `done` with the engine's result or `error` with the reason, never a retry loop.
 * The parameters carry the engine's input, so the artifact is reproducible from the row alone (§10.6).
 */
class ReportRunner(
    private val jobs: ReportJobs,
    private val json: ObjectMapper,
    private val agents: AgentsClient,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(ReportRunner::class.java)
    private val heartbeat =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "report-job-heartbeat").apply { isDaemon = true }
        }

    override fun destroy() {
        heartbeat.shutdownNow()
    }

    @Scheduled(fixedDelayString = "\${octo.reports.poll-ms:5000}")
    fun poll() {
        while (true) {
            val job = jobs.claimNext() ?: return
            runOne(job)
        }
    }

    fun runOne(job: ReportJob): ReportJob {
        val claimToken = requireNotNull(job.claimToken) { "report job ${job.id} is not claimed" }
        val renewal =
            heartbeat.scheduleAtFixedRate(
                {
                    try {
                        if (!jobs.renew(job.id, claimToken)) log.warn("report job {} lost its lease", job.id)
                    } catch (e: Exception) {
                        log.warn("report job {} lease renewal failed", job.id, e)
                    }
                },
                60,
                60,
                TimeUnit.SECONDS,
            )
        return try {
            val result = json.writeValueAsString(execute(job))
            jobs.complete(job.id, claimToken, result, sha256(result))
        } catch (e: Exception) {
            log.warn("report job {} failed: {}", job.id, e.message)
            jobs.fail(job.id, claimToken, e.message ?: e::class.simpleName ?: "failed")
        } finally {
            renewal.cancel(false)
        }
    }

    private fun execute(job: ReportJob): Map<String, Any?> =
        when (job.request.type) {
            ReportType.PERFORMANCE -> performanceReport(job)
            ReportType.GL_EXPORT -> glExportReport(job)
            ReportType.LP_REPORT -> lpReport(job)
            ReportType.ATTRIBUTION -> attributionReport(job)
            // TODO(#105): exposure over lookThrough() needs its input shape agreed.
            ReportType.EXPOSURE -> error("report type ${job.request.type.wireValue} is not supported yet")
        }

    /** Position source `inline-series`: the caller supplies the investor-signed series (§10.2) in the parameters. */
    private fun performanceReport(job: ReportJob): Map<String, Any?> {
        require(job.request.positionSourceType == "inline-series") {
            "position source ${job.request.positionSourceType} is not supported; pass an inline-series until attribution is resolved from the graph store"
        }
        val input = json.readValue<PerformanceInput>(job.request.parameters)
        val series =
            CashFlowSeries(
                Currency.getInstance(input.currency),
                input.flows.map { CashFlow(it.date, it.amount) },
                input.nav,
                input.valuationDate,
            )
        val report = performance(series)
        val all =
            mapOf(
                "paidIn" to report.paidIn,
                "distributed" to report.distributed,
                "nav" to report.nav,
                "dpi" to report.dpi,
                "rvpi" to report.rvpi,
                "tvpi" to report.tvpi,
                "irr" to report.irr,
            )
        val unknown = job.request.measures - all.keys
        require(unknown.isEmpty()) { "unknown performance measures $unknown; available: ${all.keys}" }
        val selected = if (job.request.measures.isEmpty()) all else all.filterKeys { it in job.request.measures }
        return selected +
            mapOf("currency" to report.currency.currencyCode, "valuationDate" to report.valuationDate, "methodology" to report.methodology)
    }

    data class PerformanceInput(
        val currency: String,
        val valuationDate: LocalDate,
        val nav: BigDecimal,
        val flows: List<Flow>,
    ) {
        data class Flow(
            val date: LocalDate,
            val amount: BigDecimal,
        )
    }

    /**
     * Attribution (#6 slice 3): single-period Brinson over the sector weights and returns the caller supplies
     * inline (position source `inline-sectors`, mirroring performance's `inline-series` until benchmark
     * weights are resolved from the graph store). Effects that are undefined for the inputs stay null (§10.7).
     */
    private fun attributionReport(job: ReportJob): Map<String, Any?> {
        require(job.request.positionSourceType == "inline-sectors") {
            "position source ${job.request.positionSourceType} is not supported; pass inline-sectors until benchmark weights are resolved from the graph store"
        }
        val input = json.readValue<AttributionInput>(job.request.parameters)
        val report =
            brinson(
                Currency.getInstance(input.currency),
                input.benchmark,
                input.periodStart,
                input.periodEnd,
                input.sectors.map {
                    SectorPerformance(it.sector, it.portfolioWeight, it.benchmarkWeight, it.portfolioReturn, it.benchmarkReturn)
                },
            )
        val all =
            mapOf(
                "portfolioReturn" to report.portfolioReturn,
                "benchmarkReturn" to report.benchmarkReturn,
                "activeReturn" to report.activeReturn,
                "allocation" to report.allocation,
                "selection" to report.selection,
                "interaction" to report.interaction,
                "sectors" to
                    report.sectors.map {
                        mapOf(
                            "sector" to it.sector,
                            "allocation" to it.allocation,
                            "selection" to it.selection,
                            "interaction" to it.interaction,
                        )
                    },
            )
        val unknown = job.request.measures - all.keys
        require(unknown.isEmpty()) { "unknown attribution measures $unknown; available: ${all.keys}" }
        val selected = if (job.request.measures.isEmpty()) all else all.filterKeys { it in job.request.measures }
        return selected +
            mapOf(
                "currency" to report.currency.currencyCode,
                "benchmark" to report.benchmark,
                "periodStart" to report.periodStart,
                "periodEnd" to report.periodEnd,
                "methodology" to report.methodology,
            )
    }

    data class AttributionInput(
        val currency: String,
        val benchmark: String,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val sectors: List<SectorInput>,
    ) {
        /** A return may be omitted only where its weight is zero (the engine enforces it). */
        data class SectorInput(
            val sector: String,
            val portfolioWeight: BigDecimal,
            val benchmarkWeight: BigDecimal,
            val portfolioReturn: BigDecimal? = null,
            val benchmarkReturn: BigDecimal? = null,
        )
    }

    /**
     * GL export (#6 slice 15): the caller supplies the ledger events inline (position source
     * `inline-events`, mirroring performance's `inline-series` until graph-store sourcing lands). The
     * artifact is a balanced double-entry journal reproducible from the row alone (§10.6).
     */
    private fun glExportReport(job: ReportJob): Map<String, Any?> {
        require(job.request.positionSourceType == "inline-events") {
            "position source ${job.request.positionSourceType} is not supported; pass inline-events until ledger sourcing is resolved from the graph store"
        }
        val input = json.readValue<GlExportInput>(job.request.parameters)
        val events =
            input.events.map { e ->
                LedgerEvent(
                    id = e.id,
                    flowType = FlowType.entries.first { it.wireValue == e.flowType },
                    amount = e.amount,
                    currency = Currency.getInstance(e.currency),
                    occurredAt = e.occurredAt,
                    recordedAt = e.recordedAt,
                    supersedesId = e.supersedesId,
                )
            }
        val journal = glJournal(events, input.knownAt, ZoneId.of(input.zone))
        return mapOf(
            "asOf" to journal.asOf,
            "methodology" to journal.methodology,
            "debitsByCurrency" to journal.debitsByCurrency.mapKeys { it.key.currencyCode },
            "creditsByCurrency" to journal.creditsByCurrency.mapKeys { it.key.currencyCode },
            "lines" to
                journal.lines.map { line ->
                    mapOf(
                        "sourceEventId" to line.sourceEventId,
                        "date" to line.date,
                        "currency" to line.currency.currencyCode,
                        "flowType" to line.flowType.wireValue,
                        "debit" to mapOf("code" to line.debit.code, "name" to line.debit.name),
                        "credit" to mapOf("code" to line.credit.code, "name" to line.credit.name),
                        "amount" to line.amount,
                    )
                },
        )
    }

    data class GlExportInput(
        val knownAt: Instant,
        val events: List<EventInput>,
        val zone: String = "UTC",
    ) {
        data class EventInput(
            val flowType: String,
            val amount: BigDecimal,
            val currency: String,
            val occurredAt: Instant,
            val recordedAt: Instant,
            val id: UUID = UUID.randomUUID(),
            val supersedesId: UUID? = null,
        )
    }

    /**
     * LP report (ADR-0005 F8): the sidecar narrates the job's own parameters into an LP letter.
     * The draft lands as the job's `done` result only when jev's support/completeness gate passes;
     * a refusal errors the job and the judged draft stays auditable on `agent_run` under
     * `report-job:{id}`. Outbound release is still the approval task of `/release` — the artifact
     * is sealed until a human approves it.
     */
    private fun lpReport(job: ReportJob): Map<String, Any?> {
        val outcome =
            agents.run(
                "lp-report",
                mapOf(
                    "tenant_id" to job.request.tenantId.toString(),
                    "run_key" to "report-job:${job.id}",
                    "job_id" to job.id.toString(),
                    "position_source_type" to job.request.positionSourceType,
                    "position_source_id" to job.request.positionSourceId,
                    "measures" to job.request.measures,
                    "parameters" to
                        json.readValue(
                            job.request.parameters,
                            object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {},
                        ),
                ),
            )
        require(outcome["status"] == "completed") {
            "lp-report ${outcome["status"] ?: "failed"}: ${outcome["stage_note"] ?: "the jev gate refused the draft"}"
        }
        return outcome
    }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
