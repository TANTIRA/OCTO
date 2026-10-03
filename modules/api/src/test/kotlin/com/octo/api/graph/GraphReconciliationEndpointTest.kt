package com.octo.api.graph

import com.octo.api.OctoApplication
import com.octo.api.access.PlatformAdmin
import com.octo.workflow.Task
import com.octo.workflow.TaskState
import com.octo.workflow.opened
import com.octo.workflow.persistence.TaskProvenance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import javax.sql.DataSource

/**
 * `GET /api/v1/admin/graph/reconciliation` gated the same way tenant provisioning is: a platform
 * admin gets the live report — which also opens the drift tasks — and every other caller is
 * denied (#564). `NEO4J_URI` stays unset so the conditional graph beans (and the scanned
 * controller) stay out; the endpoint is supplied by [Wiring] with a planted runner, which also
 * exercises that the route maps from a bean-registered controller. [Wiring] is a
 * `@TestConfiguration` — component scans ignore it, so it can never leak into another context.
 */
class GraphReconciliationEndpointTest {
    private companion object {
        val PLATFORM_ADMIN: UUID = UUID.randomUUID()
        val TENANT_ID: UUID = UUID.randomUUID()
        val OCTO_ID: UUID = UUID.randomUUID()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Wiring {
        val opened = mutableListOf<Task>()

        @Bean
        fun graphReconciliationTestController(): GraphReconciliationController {
            val runner =
                GraphReconciliationRunner(
                    DriverManagerDataSource("jdbc:postgresql://unused"),
                    {
                        GraphReconciliation(
                            TENANT_ID,
                            checked = 4,
                            discrepancies =
                                listOf(GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "asset", OCTO_ID, "no node")),
                        )
                    },
                    GraphTaskOpener { task: Task, _: TaskProvenance -> opened += task; opened(task) },
                    null,
                )
            return GraphReconciliationController(runner, PlatformAdmin(PLATFORM_ADMIN.toString()))
        }

        @Bean
        fun unusedDataSource(): DataSource = DriverManagerDataSource("jdbc:postgresql://unused")
    }

    private fun run(block: (MockMvc, Wiring) -> Unit) {
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java, Wiring::class.java)
            .withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            ).run { context ->
                block(
                    MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build(),
                    context.getBean(Wiring::class.java),
                )
            }
    }

    @Test
    fun `a platform admin gets the live report and the discrepancy's task opens`() {
        run { mvc, wiring ->
            mvc
                .perform(
                    get("/api/v1/admin/graph/reconciliation")
                        .param("tenantId", TENANT_ID.toString())
                        .with(jwt().jwt { it.subject(PLATFORM_ADMIN.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.clean").value(false))
                .andExpect(jsonPath("$.checked").value(4))
                .andExpect(jsonPath("$.discrepancies[0].kind").value("missing"))
                .andExpect(jsonPath("$.discrepancies[0].octoId").value(OCTO_ID.toString()))
            assertThat(wiring.opened.single().subjectId).isEqualTo("$TENANT_ID:missing:asset:$OCTO_ID")
        }
    }

    @Test
    fun `tenant callers and anonymous callers are denied`() {
        run { mvc, wiring ->
            mvc
                .perform(
                    get("/api/v1/admin/graph/reconciliation")
                        .param("tenantId", TENANT_ID.toString())
                        .with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isForbidden)
            mvc
                .perform(get("/api/v1/admin/graph/reconciliation").param("tenantId", TENANT_ID.toString()))
                .andExpect(status().isForbidden)
            assertThat(wiring.opened).isEmpty()
        }
    }
}
