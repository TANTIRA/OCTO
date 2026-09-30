package com.octo.api.agents

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.agents.persistence.AgentRun
import com.octo.api.agents.persistence.AgentRunRecord
import com.octo.api.agents.persistence.AgentRunStatus
import com.octo.api.agents.persistence.AgentRuns
import com.octo.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/**
 * `/api/v1/agent-runs` end to end with a stubbed store and directory: members of the right role
 * record, finish and read runs inside their own tenant; viewers read but never write; run_key
 * replays read the existing row back — the sidecar's retry path is idempotent by contract.
 */
class AgentRunsEndpointTest {
    private val member = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val runs = FakeAgentRuns()
    private var agentsBehavior: (Map<String, Any>) -> Map<String, Any> = {
        mapOf("status" to "completed", "analyzed" to 0)
    }
    private val agents = AgentsClient { _, payload -> agentsBehavior(payload) }

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier {
                    TenantDirectory { id ->
                        when (id) {
                            member -> listOf(TenantAccess(tenantId, "acme", TenantRole.ANALYST))
                            viewer -> listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
                            else -> emptyList()
                        }
                    }
                },
                { it.isPrimary = true },
            ).withBean(
                AgentRuns::class.java,
                Supplier { runs },
                { it.isPrimary = true },
            ).withBean(
                AgentsClient::class.java,
                Supplier { agents },
                { it.isPrimary = true },
            ).withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun MockMvc.recorded(
        caller: UUID,
        runKey: String,
    ): String =
        perform(
            post("/api/v1/agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"tenantId":"$tenantId","workflow":"screening-dd","runKey":"$runKey",""" +
                        """"subjectType":"prospect","subjectId":"p-1","input":{"p":"p-1"},""" +
                        """"models":{"judge":"typesafe/jev-1.13"}}""",
                ).with(jwt().jwt { it.subject(caller.toString()) }),
        ).andReturn()
            .response.contentAsString
            .let {
                com.fasterxml.jackson.databind
                    .ObjectMapper()
                    .readTree(it)["id"]
                    .asText()
            }

    @Test
    fun `record opens a running row and a run_key replay returns the existing row`() {
        run { mvc ->
            val id = mvc.recorded(member, "rk-1")
            mvc
                .perform(
                    post("/api/v1/agent-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","workflow":"screening-dd","runKey":"rk-1",""" +
                                """"subjectType":"prospect","subjectId":"p-1","input":{},"models":{}}""",
                        ).with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("running"))
        }
    }

    @Test
    fun `finish closes exactly once and outcome lands once`() {
        run { mvc ->
            val id = mvc.recorded(member, "rk-2")
            mvc
                .perform(
                    post("/api/v1/agent-runs/$id/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"status":"completed","verdict":{"advance":{"noul":0.8}}}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.verdict.advance.noul").value(0.8))
            mvc
                .perform(
                    post("/api/v1/agent-runs/$id/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"status":"failed"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
            mvc
                .perform(
                    post("/api/v1/agent-runs/$id/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"outcome":{"decision":"advanced"}}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.humanOutcome.decision").value("advanced"))
            mvc
                .perform(
                    post("/api/v1/agent-runs/$id/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"outcome":{"decision":"rejected"}}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
        }
    }

    @Test
    fun `viewers read but never write, non-members see 404, bad input is 400`() {
        run { mvc ->
            val id = mvc.recorded(member, "rk-3")
            // Reads are membership-level: the viewer lists and loads inside the boundary.
            mvc
                .perform(
                    get("/api/v1/agent-runs?tenantId=$tenantId&subjectType=prospect")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$[0].id").value(id))
            mvc
                .perform(get("/api/v1/agent-runs/$id").with(jwt().jwt { it.subject(viewer.toString()) }))
                .andExpect(status().isOk)
            // Writes are working access: the viewer and the non-member both see 404.
            mvc
                .perform(
                    post("/api/v1/agent-runs/$id/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"status":"completed"}""")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/agent-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","workflow":"w","runKey":"x",""" +
                                """"subjectType":"prospect","subjectId":"p","input":{},"models":{}}""",
                        ).with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/agent-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","workflow":"Bad Name","runKey":"x",""" +
                                """"subjectType":"prospect","subjectId":"p","input":{},"models":{}}""",
                        ).with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `calibration proxies to the sidecar member-scoped and maps its error contract`() {
        run { mvc ->
            var seenPayload: Map<String, Any>? = null
            agentsBehavior = { payload ->
                seenPayload = payload
                mapOf("status" to "completed")
            }
            mvc
                .perform(
                    post("/api/v1/agent-runs/calibration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            assertThat(seenPayload?.get("tenant_id")).isEqualTo(tenantId.toString())
            assertThat(seenPayload?.get("run_key").toString()).startsWith("calibration:")

            mvc
                .perform(
                    post("/api/v1/agent-runs/calibration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)

            agentsBehavior = { throw AgentsUnavailableException(java.io.IOException("down")) }
            mvc
                .perform(
                    post("/api/v1/agent-runs/calibration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isServiceUnavailable)

            agentsBehavior = { throw AgentsCallException(500, "sidecar broke") }
            mvc
                .perform(
                    post("/api/v1/agent-runs/calibration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadGateway)
        }
    }

    private fun MockMvc.recordSubject(
        subjectId: String,
        runKey: String,
    ) = perform(
        post("/api/v1/agent-runs")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """{"tenantId":"$tenantId","workflow":"screening-dd","runKey":"$runKey",""" +
                    """"subjectType":"prospect","subjectId":"$subjectId","input":{},"models":{}}""",
            ).with(jwt().jwt { it.subject(member.toString()) }),
    )

    @Test
    fun `blank or oversized subjects are rejected without recording a run`() {
        run { mvc ->
            for ((index, subject) in listOf("", "   ", "x".repeat(201), "é".repeat(201)).withIndex()) {
                mvc.recordSubject(subject, "invalid-$index").andExpect(status().isBadRequest)
            }
            assertThat(runs.list(tenantId, null, null, 200, TenantScope.User(member))).isEmpty()
        }
    }

    @Test
    fun `subjects within the length limit are stored unchanged`() {
        run { mvc ->
            val subjects = listOf("x", "x".repeat(200), "é".repeat(200), "company/acme/2026-09-30")
            for ((index, subject) in subjects.withIndex()) {
                mvc.recordSubject(subject, "valid-$index").andExpect(status().isCreated)
                assertThat(runs.loadByKey(tenantId, "valid-$index", TenantScope.User(member))?.subjectId)
                    .isEqualTo(subject)
            }
        }
    }

    @Test
    fun `run keys still admit 200 characters and reject 201 without a write`() {
        run { mvc ->
            mvc.recordSubject("p-1", "k".repeat(200)).andExpect(status().isCreated)
            mvc.recordSubject("p-1", "k".repeat(201)).andExpect(status().isBadRequest)
            assertThat(runs.list(tenantId, null, null, 200, TenantScope.User(member))).hasSize(1)
        }
    }

    private class FakeAgentRuns : AgentRuns {
        private val rows = mutableMapOf<UUID, AgentRun>()

        override fun record(
            record: AgentRunRecord,
            scope: TenantScope,
        ): UUID? {
            if (rows.values.any { it.tenantId == record.tenantId && it.runKey == record.runKey }) return null
            val id = UUID.randomUUID()
            rows[id] =
                AgentRun(
                    id,
                    record.tenantId,
                    record.workflow,
                    record.runKey,
                    record.subjectType,
                    record.subjectId,
                    AgentRunStatus.RUNNING,
                    record.actor,
                    record.input,
                    null,
                    null,
                    record.models,
                    record.thresholds,
                    record.requestIds,
                    null,
                    null,
                    Instant.now(),
                    null,
                )
            return id
        }

        override fun finish(
            id: UUID,
            status: AgentRunStatus,
            output: String?,
            verdict: String?,
            error: String?,
            scope: TenantScope,
        ): Boolean {
            val row = rows[id] ?: return false
            if (row.status != AgentRunStatus.RUNNING) return false
            rows[id] =
                row.copy(status = status, output = output, verdict = verdict, error = error, finishedAt = Instant.now())
            return true
        }

        override fun recordOutcome(
            id: UUID,
            outcome: String,
            scope: TenantScope,
        ): Boolean {
            val row = rows[id] ?: return false
            if (row.humanOutcome != null || row.status == AgentRunStatus.RUNNING) return false
            rows[id] = row.copy(humanOutcome = outcome)
            return true
        }

        override fun loadByKey(
            tenantId: UUID,
            runKey: String,
            scope: TenantScope,
        ): AgentRun? = rows.values.firstOrNull { it.tenantId == tenantId && it.runKey == runKey }

        override fun load(
            id: UUID,
            scope: TenantScope,
        ): AgentRun? = rows[id]

        override fun list(
            tenantId: UUID,
            subjectType: String?,
            subjectId: String?,
            limit: Int,
            scope: TenantScope,
        ): List<AgentRun> =
            rows.values
                .filter { it.tenantId == tenantId }
                .filter { subjectType == null || it.subjectType == subjectType }
                .filter { subjectId == null || it.subjectId == subjectId }
                .sortedByDescending { it.createdAt }
                .take(limit)
    }
}
