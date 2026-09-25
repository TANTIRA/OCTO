package com.mesta.asset.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mesta.asset.analytics.CashFlow
import com.mesta.asset.analytics.CashFlowSeries
import com.mesta.asset.analytics.performance
import com.mesta.asset.workflow.report.ReportJob
import com.mesta.asset.workflow.report.ReportJobs
import com.mesta.asset.workflow.report.ReportType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Currency

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

    @Scheduled(fixedDelayString = "\${mesta.reports.poll-ms:5000}")
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
            // TODO(#105): exposure over lookThrough() and attribution over brinson() need their input shapes agreed.
            ReportType.EXPOSURE, ReportType.ATTRIBUTION -> error("report type ${job.request.type.wireValue} is not supported yet")
        }

    /** Position source `inline-series`: the caller supplies the investor-signed series (§10.2) in the parameters. */
    private fun performanceReport(job: ReportJob): Map<String, Any?> {
        require(job.request.positionSourceType == "inline-series") {
            "position source ${job.request.positionSourceType} is not supported; pass an inline-series until attribution is resolved from TypeDB"
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

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
