package com.mesta.asset.api.compliance

import com.mesta.asset.api.MestaAssetApplication
import com.mesta.asset.api.access.TenantAccess
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.recon.compliance.persistence.ComplianceStore
import com.mesta.asset.workflow.Task
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

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(MestaAssetApplication::class.java)
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
            .withBean(TaskOpener::class.java, Supplier { TaskOpener { task, _ -> opened += task } }, { it.isPrimary = true })
            .withPropertyValues(
                "mesta.reports.poll=false",
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
}
