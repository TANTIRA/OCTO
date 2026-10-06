package com.octo.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.octo.api.agents.AgentsClient
import com.octo.persistence.TenantScope
import com.octo.workflow.report.JobStatus
import com.octo.workflow.report.ReportRequest
import com.octo.workflow.report.ReportType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReportRunnerTest {
    private val jobs = FakeReportJobs()
    private val json = ObjectMapper().registerKotlinModule().findAndRegisterModules()
    private var agentResponse: Map<String, Any> =
        mapOf("status" to "completed", "memo" to "quarterly letter")
    private val agents = AgentsClient { _, _ -> agentResponse }
    private val runner = ReportRunner(jobs, json, agents)

    private fun request(
        type: ReportType = ReportType.PERFORMANCE,
        measures: List<String> = listOf("tvpi", "dpi"),
        source: String = "inline-series",
    ) = ReportRequest(
        UUID.randomUUID(),
        type,
        source,
        "fund-1",
        measures,
        """{"currency": "USD", "valuationDate": "2026-06-30", "nav": "60",
           "flows": [{"date": "2024-01-01", "amount": "-100"}, {"date": "2025-01-01", "amount": "70"}]}""",
        "analyst-1",
        UUID.randomUUID(),
    )

    @Test
    fun `a performance job runs the methodology 2 engine on the inline series and keeps the requested measures`() {
        jobs.submit(request(), TenantScope.All)
        jobs.submit(request(measures = emptyList()), TenantScope.All)
        runner.poll()

        val (selected, all) = jobs.jobs.values.toList()
        assertThat(selected.status).isEqualTo(JobStatus.DONE)
        val result = json.readTree(selected.result)
        assertThat(result["tvpi"].decimalValue()).isEqualByComparingTo("1.3") // (70 + 60) / 100
        assertThat(result["dpi"].decimalValue()).isEqualByComparingTo("0.7")
        assertThat(result.has("irr")).isFalse()
        assertThat(result["methodology"].asText()).isEqualTo("quantitative-methodology §2 v1")
        assertThat(selected.artifactSha256).hasSize(64)
        assertThat(json.readTree(all.result).has("irr")).isTrue()
    }

    @Test
    fun `an unsupported type, source or measure ends in error with the reason, and the queue keeps draining`() {
        jobs.submit(request(type = ReportType.EXPOSURE), TenantScope.All)
        jobs.submit(request(source = "commitment"), TenantScope.All)
        jobs.submit(request(measures = listOf("moic")), TenantScope.All)
        jobs.submit(request(), TenantScope.All)
        runner.poll()

        val (exposure, commitment, moic, ok) = jobs.jobs.values.toList()
        assertThat(exposure.status).isEqualTo(JobStatus.ERROR)
        assertThat(exposure.error).contains("exposure")
        assertThat(commitment.error).contains("inline-series")
        assertThat(moic.error).contains("moic")
        assertThat(ok.status).isEqualTo(JobStatus.DONE)
        assertThat(jobs.claimNext()).isNull()
    }

    private fun attributionRequest(
        source: String = "inline-sectors",
        measures: List<String> = emptyList(),
        sectors: String =
            """[{"sector": "Tech", "portfolioWeight": "0.6", "benchmarkWeight": "0.5",
                 "portfolioReturn": "0.10", "benchmarkReturn": "0.08"},
                {"sector": "Energy", "portfolioWeight": "0.4", "benchmarkWeight": "0.5",
                 "portfolioReturn": "0.02", "benchmarkReturn": "0.04"}]""",
    ) = ReportRequest(
        UUID.randomUUID(),
        ReportType.ATTRIBUTION,
        source,
        "fund-1",
        measures,
        """{"currency": "USD", "benchmark": "MSCI World", "periodStart": "2026-01-01", "periodEnd": "2026-06-30",
           "sectors": $sectors}""",
        "analyst-1",
        UUID.randomUUID(),
    )

    @Test
    fun `an attribution job runs the methodology 4_2 engine on the inline sectors and keeps the requested measures`() {
        jobs.submit(attributionRequest(measures = listOf("allocation", "selection", "interaction", "activeReturn")), TenantScope.All)
        jobs.submit(attributionRequest(), TenantScope.All)
        runner.poll()

        val (selected, all) = jobs.jobs.values.toList()
        assertThat(selected.status).isEqualTo(JobStatus.DONE)
        val result = json.readTree(selected.result)
        // Tech and Energy each add 0.002 allocation and 0.002 interaction; selection nets to zero (+0.01, -0.01).
        assertThat(result["allocation"].decimalValue()).isEqualByComparingTo("0.004")
        assertThat(result["selection"].decimalValue()).isEqualByComparingTo("0")
        assertThat(result["interaction"].decimalValue()).isEqualByComparingTo("0.004")
        assertThat(result["activeReturn"].decimalValue()).isEqualByComparingTo("0.008") // 0.068 - 0.060
        assertThat(result.has("portfolioReturn")).isFalse()
        assertThat(result["methodology"].asText()).isEqualTo("quantitative-methodology §4.2 v1")
        assertThat(selected.artifactSha256).hasSize(64)
        val full = json.readTree(all.result)
        assertThat(full["sectors"]).hasSize(2)
        assertThat(full["portfolioReturn"].decimalValue()).isEqualByComparingTo("0.068")
    }

    @Test
    fun `an attribution job with a wrong source, unknown measure or weights that do not sum to one ends in error`() {
        jobs.submit(attributionRequest(source = "fund"), TenantScope.All)
        jobs.submit(attributionRequest(measures = listOf("alpha")), TenantScope.All)
        jobs.submit(
            attributionRequest(
                sectors = """[{"sector": "Tech", "portfolioWeight": "0.9", "benchmarkWeight": "1",
                               "portfolioReturn": "0.10", "benchmarkReturn": "0.08"}]""",
            ),
            TenantScope.All,
        )
        jobs.submit(attributionRequest(), TenantScope.All)
        runner.poll()

        val (source, measure, weights, ok) = jobs.jobs.values.toList()
        assertThat(source.error).contains("inline-sectors")
        assertThat(measure.error).contains("alpha")
        assertThat(weights.error).contains("weights sum")
        assertThat(ok.status).isEqualTo(JobStatus.DONE)
    }

    @Test
    fun `an lp-report job ships its parameters to the sidecar and lands the judged memo as the result`() {
        var seen: Map<String, Any>? = null
        val capture =
            AgentsClient { _, payload ->
                seen = payload
                agentResponse
            }
        val runner = ReportRunner(jobs, json, capture)
        jobs.submit(request(type = ReportType.LP_REPORT, source = "inline-series"), TenantScope.All)
        runner.poll()

        val job = jobs.jobs.values.single()
        assertThat(job.status).isEqualTo(JobStatus.DONE)
        assertThat(seen!!["run_key"]).isEqualTo("report-job:${job.id}")
        assertThat(seen!!["job_id"]).isEqualTo(job.id.toString())
        assertThat(json.readTree(job.result)["memo"].asText()).isEqualTo("quarterly letter")
    }

    @Test
    fun `an lp-report the jev gate refuses ends in error, not a releasable done`() {
        agentResponse = mapOf("status" to "refused", "stage_note" to "the jev gate refused the draft")
        jobs.submit(request(type = ReportType.LP_REPORT), TenantScope.All)
        runner.poll()

        val job = jobs.jobs.values.single()
        assertThat(job.status).isEqualTo(JobStatus.ERROR)
        assertThat(job.error).contains("refused")
    }
}
