package com.octo.api

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.octo.api.agents.AgentsClient
import com.octo.api.report.ReportRunner
import com.octo.persistence.TenantScope
import com.octo.workflow.report.JobStatus
import com.octo.workflow.report.ReportJob
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportRequest
import com.octo.workflow.report.ReportType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ReportRunner's GL-export branch (#6 slice 15): an inline-events job produces a balanced double-entry
 * journal artifact. Pure — no DB — so it runs without Docker.
 */
class ReportRunnerGlExportTest {
    private val json =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .addModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()

    private class CapturingJobs : ReportJobs {
        var completed: String? = null

        override fun complete(
            id: UUID,
            claimToken: UUID,
            result: String,
            artifactSha256: String?,
        ): ReportJob {
            completed = result
            return done(id, result, artifactSha256)
        }

        override fun fail(
            id: UUID,
            claimToken: UUID,
            error: String,
        ): ReportJob = throw AssertionError("job failed: $error")

        override fun renew(
            id: UUID,
            claimToken: UUID,
        ) = true

        override fun submit(
            request: ReportRequest,
            scope: TenantScope,
        ) = throw NotImplementedError()

        override fun load(
            id: UUID,
            scope: TenantScope,
        ) = throw NotImplementedError()

        override fun pendingCount(
            tenantId: UUID,
            scope: TenantScope,
        ) = throw NotImplementedError()

        override fun claimNext() = throw NotImplementedError()

        override fun attachApproval(
            id: UUID,
            taskId: UUID,
        ) = throw NotImplementedError()

        private fun done(
            id: UUID,
            result: String,
            artifactSha256: String?,
        ) = ReportJob(
            id = id,
            request =
                ReportRequest(
                    tenantId = UUID.randomUUID(),
                    type = ReportType.GL_EXPORT,
                    positionSourceType = "inline-events",
                    positionSourceId = "fund-1",
                    measures = emptyList(),
                    parameters = "{}",
                    requestedBy = "analyst",
                    correlationId = UUID.randomUUID(),
                ),
            status = JobStatus.DONE,
            result = result,
            error = null,
            artifactSha256 = artifactSha256,
            approvalTaskId = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }

    private fun job(parameters: String) =
        ReportJob(
            id = UUID.randomUUID(),
            request =
                ReportRequest(
                    tenantId = UUID.randomUUID(),
                    type = ReportType.GL_EXPORT,
                    positionSourceType = "inline-events",
                    positionSourceId = "fund-1",
                    measures = emptyList(),
                    parameters = parameters,
                    requestedBy = "analyst",
                    correlationId = UUID.randomUUID(),
                ),
            status = JobStatus.EXECUTING,
            claimToken = UUID.randomUUID(),
            claimedUntil = Instant.now().plusSeconds(300),
            result = null,
            error = null,
            artifactSha256 = null,
            approvalTaskId = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `a gl-export job posts a balanced journal`() {
        val jobs = CapturingJobs()
        val params =
            """
            {"knownAt":"2025-01-01T00:00:00Z","zone":"UTC","events":[
              {"flowType":"contribution","amount":-100,"currency":"USD",
               "occurredAt":"2022-06-01T12:00:00Z","recordedAt":"2022-06-01T12:00:00Z"},
              {"flowType":"distribution","amount":40,"currency":"USD",
               "occurredAt":"2023-06-01T12:00:00Z","recordedAt":"2023-06-01T12:00:00Z"}
            ]}
            """.trimIndent()

        ReportRunner(jobs, json, AgentsClient { _, _ -> error("gl-export never calls the sidecar") }).runOne(job(params))

        val result: Map<String, Any?> = json.readValue(jobs.completed ?: error("job did not complete"))
        val lines = result["lines"] as List<*>
        assertThat(lines).hasSize(2)
        assertThat(result["debitsByCurrency"]).isEqualTo(result["creditsByCurrency"])

        @Suppress("UNCHECKED_CAST")
        val debits = result["debitsByCurrency"] as Map<String, Any?>
        assertThat(debits["USD"].toString()).isEqualTo("140")

        @Suppress("UNCHECKED_CAST")
        val contribution = lines.map { it as Map<String, Any?> }.first { it["flowType"] == "contribution" }
        assertThat((contribution["debit"] as Map<*, *>)["code"]).isEqualTo("1200")
        assertThat((contribution["credit"] as Map<*, *>)["code"]).isEqualTo("1000")
        assertThat(contribution["amount"].toString()).isEqualTo("100")
    }
}
