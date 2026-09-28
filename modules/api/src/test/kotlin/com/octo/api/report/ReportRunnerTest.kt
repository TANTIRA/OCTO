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
