package com.mesta.asset.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.mesta.asset.workflow.report.JobStatus
import com.mesta.asset.workflow.report.ReportRequest
import com.mesta.asset.workflow.report.ReportType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReportRunnerTest {
    private val jobs = FakeReportJobs()
    private val json = ObjectMapper().registerKotlinModule().findAndRegisterModules()
    private val runner = ReportRunner(jobs, json)

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
        jobs.submit(request())
        jobs.submit(request(measures = emptyList()))
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
        jobs.submit(request(type = ReportType.EXPOSURE))
        jobs.submit(request(source = "commitment"))
        jobs.submit(request(measures = listOf("moic")))
        jobs.submit(request())
        runner.poll()

        val (exposure, commitment, moic, ok) = jobs.jobs.values.toList()
        assertThat(exposure.status).isEqualTo(JobStatus.ERROR)
        assertThat(exposure.error).contains("exposure")
        assertThat(commitment.error).contains("inline-series")
        assertThat(moic.error).contains("moic")
        assertThat(ok.status).isEqualTo(JobStatus.DONE)
        assertThat(jobs.claimNext()).isNull()
    }
}
