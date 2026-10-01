package com.octo.api.compliance

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.agents.AgentsCallException
import com.octo.api.agents.AgentsClient
import com.octo.api.agents.AgentsUnavailableException
import com.octo.persistence.TenantScope
import com.octo.recon.compliance.ComplianceCheck
import com.octo.recon.compliance.persistence.ComplianceProvenance
import com.octo.recon.compliance.persistence.ComplianceStore
import com.octo.recon.compliance.persistence.EVALUATION_RULE_LIMIT
import com.octo.workflow.Task
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
import java.math.BigDecimal
import java.util.UUID
import java.util.function.Supplier

/** The compliance endpoints end to end over the in-memory store: roles per endpoint, a rule defined then breached, the task in the outcome. */
class ComplianceEndpointTest {
    private val approver = UUID.randomUUID()
    private val analyst = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val store = FakeComplianceStore()
    private val opened = mutableListOf<Task>()
    private var agentsBehavior: (Map<String, Any>) -> Map<String, Any> = {
        mapOf("status" to "completed", "rationale" to "the conc rule breached at 0.6 against 0.25")
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
                            approver -> listOf(TenantAccess(tenantId, "acme", TenantRole.APPROVER))
                            analyst -> listOf(TenantAccess(tenantId, "acme", TenantRole.ANALYST))
                            viewer -> listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
                            else -> emptyList()
                        }
                    }
                },
                { it.isPrimary = true },
            ).withBean(ComplianceStore::class.java, Supplier { store }, { it.isPrimary = true })
            .withBean(TaskOpener::class.java, Supplier { TaskOpener { _, task, _ -> opened += task } }, { it.isPrimary = true })
            .withBean(AgentsClient::class.java, Supplier { agents }, { it.isPrimary = true })
            .withPropertyValues(
                "octo.reports.poll=false",
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun asUser(id: UUID) = jwt().jwt { it.subject(id.toString()) }

    private fun rule(
        ruleId: String = "conc",
        check: String = """{"type": "concentration-limit", "maxFraction": "0.25"}""",
    ) = """{"tenantId": "$tenantId", "ruleId": "$ruleId", "version": 1, "name": "Concentration", "check": $check}"""

    private val evaluation =
        """{"tenantId": "$tenantId", "subject": "fund-1", "asOf": "2026-06-30",
            "exposure": {"currency": "USD", "byAsset": {"a": "60", "b": "40"}},
            "coverage": {"currency": "USD", "scenario": "base", "ratio": null}}"""

    @Test
    fun `an approver defines rules, anyone lists them, and an analyst's evaluation opens the review task`() {
        run { mvc ->
            mvc
                .perform(post("/api/v1/compliance/rules").contentType(MediaType.APPLICATION_JSON).content(rule()).with(asUser(approver)))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.check.type").value("concentration-limit"))
            mvc
                .perform(
                    post(
                        "/api/v1/compliance/rules",
                    ).contentType(
                        MediaType.APPLICATION_JSON,
                    ).content(rule("cov", """{"type": "coverage-floor", "minRatio": "1.2"}"""))
                        .with(asUser(approver)),
                ).andExpect(status().isCreated)
            mvc
                .perform(get("/api/v1/compliance/rules?tenantId=$tenantId").with(asUser(viewer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(2))

            mvc
                .perform(
                    post(
                        "/api/v1/compliance/evaluations",
                    ).contentType(MediaType.APPLICATION_JSON).content(evaluation).with(asUser(analyst)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$[0].ruleId").value("conc"))
                .andExpect(jsonPath("$[0].result").value("breach")) // 60 / 100 > 0.25
                .andExpect(jsonPath("$[0].measured.fraction").value("0.6"))
                .andExpect(jsonPath("$[0].taskId").isNotEmpty)
                .andExpect(jsonPath("$[1].result").value("not-evaluable")) // ratio null
                .andExpect(jsonPath("$[1].taskId").doesNotExist())
            assertThat(opened.single().requestedBy).isEqualTo(analyst.toString())
        }
    }

    @Test
    fun `a rule version that is not the next one is a conflict, and omitting it works`() {
        run { mvc ->
            mvc
                .perform(post("/api/v1/compliance/rules").contentType(MediaType.APPLICATION_JSON).content(rule()).with(asUser(approver)))
                .andExpect(status().isCreated)
            // the rule sits at version 1; a caller still holding 1 conflicts, a caller omitting it or guessing 2 wins
            mvc
                .perform(post("/api/v1/compliance/rules").contentType(MediaType.APPLICATION_JSON).content(rule()).with(asUser(approver)))
                .andExpect(status().isConflict)
            mvc
                .perform(
                    post("/api/v1/compliance/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rule().replace("\"version\": 1", "\"version\": 2"))
                        .with(asUser(approver)),
                ).andExpect(status().isCreated)
            mvc
                .perform(
                    post("/api/v1/compliance/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rule("cov", """{"type": "coverage-floor", "minRatio": "1.2"}""").replace(", \"version\": 1", ""))
                        .with(asUser(approver)),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.version").value(1))
        }
    }

    @Test
    fun `an approver retires a rule append-only — the tombstone hides it and an unknown rule is 404`() {
        run { mvc ->
            mvc
                .perform(post("/api/v1/compliance/rules").contentType(MediaType.APPLICATION_JSON).content(rule()).with(asUser(approver)))
                .andExpect(status().isCreated)

            mvc
                .perform(
                    post("/api/v1/compliance/rules/conc/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId": "$tenantId"}""")
                        .with(asUser(analyst)),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/compliance/rules/conc/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId": "$tenantId"}""")
                        .with(asUser(approver)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(2))
            mvc
                .perform(get("/api/v1/compliance/rules?tenantId=$tenantId").with(asUser(viewer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(0))
            mvc
                .perform(
                    post("/api/v1/compliance/rules/nope/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId": "$tenantId"}""")
                        .with(asUser(approver)),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `a rule id outside the V14 shape is 400 on both endpoints, not a 500 from the CHECK`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/compliance/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rule(ruleId = "CONC"))
                        .with(asUser(approver)),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/compliance/rules/%20/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId": "$tenantId"}""")
                        .with(asUser(approver)),
                ).andExpect(status().isBadRequest)
            assertThat(store.rules).isEmpty()
        }
    }

    @Test
    fun `an analyst cannot define rules, a viewer cannot evaluate, a bad check is 400, and no token is 403`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/compliance/rules").contentType(MediaType.APPLICATION_JSON).content(rule()).with(asUser(analyst)),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post(
                        "/api/v1/compliance/rules",
                    ).contentType(MediaType.APPLICATION_JSON).content(rule(check = """{"type": "vibes"}""")).with(asUser(approver)),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/compliance/evaluations").contentType(MediaType.APPLICATION_JSON).content(evaluation).with(asUser(viewer)),
                ).andExpect(status().isNotFound)
            mvc.perform(get("/api/v1/compliance/rules?tenantId=$tenantId").with(asUser(UUID.randomUUID()))).andExpect(status().isNotFound)
            mvc.perform(get("/api/v1/compliance/rules?tenantId=$tenantId")).andExpect(status().isForbidden)
            assertThat(store.rules).isEmpty()
            assertThat(opened).isEmpty()
        }
    }

    @Test
    fun `rationale hands engine outcomes to the sidecar, viewers and a down sidecar are denied`() {
        run { mvc ->
            mvc
                .perform(post("/api/v1/compliance/rules").contentType(MediaType.APPLICATION_JSON).content(rule()).with(asUser(approver)))
                .andExpect(status().isCreated)

            var payload: Map<String, Any>? = null
            agentsBehavior = {
                payload = it
                mapOf("status" to "completed", "rationale" to "the conc rule breached at 0.6 against 0.25")
            }
            mvc
                .perform(
                    post("/api/v1/compliance/rationale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation)
                        .with(asUser(analyst)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.rationale").isNotEmpty)
            @Suppress("UNCHECKED_CAST")
            val outcomes = payload!!["outcomes"] as List<Map<String, Any>>
            assertThat(outcomes.single()["result"]).isEqualTo("breach")
            assertThat((outcomes.single()["measured"] as Map<*, *>)["fraction"]).isEqualTo("0.6")

            mvc
                .perform(
                    post("/api/v1/compliance/rationale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation)
                        .with(asUser(viewer)),
                ).andExpect(status().isNotFound)

            agentsBehavior = { throw AgentsUnavailableException(java.io.IOException("down")) }
            mvc
                .perform(
                    post("/api/v1/compliance/rationale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation)
                        .with(asUser(analyst)),
                ).andExpect(status().isServiceUnavailable)

            agentsBehavior = { throw AgentsCallException(500, "registry miss") }
            mvc
                .perform(
                    post("/api/v1/compliance/rationale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation)
                        .with(asUser(analyst)),
                ).andExpect(status().isBadGateway)
        }
    }

    @Test
    fun `an oversized subject is a client error and a tenant over the rule limit is a conflict`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/compliance/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation.replace("\"fund-1\"", "\"${"x".repeat(301)}\""))
                        .with(asUser(analyst)),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/compliance/rationale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation.replace("\"fund-1\"", "\"${"x".repeat(301)}\""))
                        .with(asUser(analyst)),
                ).andExpect(status().isBadRequest)

            repeat(EVALUATION_RULE_LIMIT + 1) {
                store.defineRule(
                    tenantId,
                    "rule-$it",
                    "Rule $it",
                    ComplianceCheck.ConcentrationLimit(BigDecimal("0.5")),
                    null,
                    ComplianceProvenance("officer", UUID.randomUUID()),
                    TenantScope.All,
                )
            }
            mvc
                .perform(
                    post("/api/v1/compliance/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation)
                        .with(asUser(analyst)),
                ).andExpect(status().isConflict)
            assertThat(store.recorded).isEmpty()
        }
    }

    @Test
    fun `a rationale subject the run spine would refuse is 400 before any outcome or task lands`() {
        run { mvc ->
            mvc
                .perform(post("/api/v1/compliance/rules").contentType(MediaType.APPLICATION_JSON).content(rule()).with(asUser(approver)))
                .andExpect(status().isCreated)
            var sidecarCalls = 0
            agentsBehavior = {
                sidecarCalls++
                mapOf("status" to "completed", "rationale" to "ok")
            }
            // "{subject}/2026-06-30" is subject + 11 characters; 190 lands at 201, one over the run's subject_id cap.
            mvc
                .perform(
                    post("/api/v1/compliance/rationale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation.replace("\"fund-1\"", "\"${"x".repeat(190)}\""))
                        .with(asUser(analyst)),
                ).andExpect(status().isBadRequest)
            assertThat(store.recorded).isEmpty()
            assertThat(opened).isEmpty()
            assertThat(sidecarCalls).isZero()

            mvc
                .perform(
                    post("/api/v1/compliance/rationale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation.replace("\"fund-1\"", "\"${"x".repeat(189)}\""))
                        .with(asUser(analyst)),
                ).andExpect(status().isOk)
            assertThat(sidecarCalls).isEqualTo(1)
        }
    }

    @Test
    fun `an unreadable currency in the evaluation inputs is a client error`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/compliance/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluation.replace("\"USD\"", "\"NOPE\""))
                        .with(asUser(analyst)),
                ).andExpect(status().isBadRequest)
        }
    }
}
