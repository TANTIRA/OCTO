package com.octo.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.octo.analytics.CashFlow
import com.octo.analytics.CashFlowSeries
import com.octo.analytics.performance
import com.octo.iborcore.FlowType
import com.octo.iborcore.LedgerEvent
import com.octo.iborcore.glJournal
import com.octo.workflow.report.ReportJob
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.UUID

/**
 * Runs queued report jobs in process (#105: a poller, not a queue). Each poll drains the queue one claim at a
 * time; a job's outcome is `done` with the engine's result or `error` with the reason, never a retry loop.
 * The parameters carry the engine's input, so the artifact is reproducible from the row alone (§10.6).
 */
class ReportRunner(
    private val jobs: ReportJobs,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ReportRunner::class.java)

    @Scheduled(fixedDelayString = "\${octo.reports.poll-ms:5000}")
    fun poll() {
        while (true) {
            val job = jobs.claimNext() ?: return
            runOne(job)
        }
    }

    fun runOne(job: ReportJob): ReportJob =
        try {
            val result = json.writeValueAsString(execute(job))
            jobs.complete(job.id, result, sha256(result))
        } catch (e: Exception) {
            log.warn("report job {} failed: {}", job.id, e.message)
            jobs.fail(job.id, e.message ?: e::class.simpleName ?: "failed")
        }

    private fun execute(job: ReportJob): Map<String, Any?> =
        when (job.request.type) {
            ReportType.PERFORMANCE -> performanceReport(job)
            ReportType.GL_EXPORT -> glExportReport(job)
            // TODO(#105): exposure over lookThrough() and attribution over brinson() need their input shapes agreed.
            ReportType.EXPOSURE, ReportType.ATTRIBUTION -> error("report type ${job.request.type.wireValue} is not supported yet")
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

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
