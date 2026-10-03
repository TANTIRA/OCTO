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
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import java.util.function.Supplier

/**
 * `GET /api/v1/admin/graph/reconciliation` gated the same way tenant provisioning is: a platform
 * admin gets the live report — which also opens the drift tasks — and every other caller is
 * denied (#564). The graph beans themselves only exist under `NEO4J_URI`, so the controller is
 * wired here with a runner whose reconcile answer is planted.
 */
class GraphReconciliationEndpointTest {
    private val platformAdmin = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val octoId = UUID.randomUUID()

    private val opened = mutableListOf<Task>()
    private val runner =
        GraphReconciliationRunner(
            org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://unused"),
            {
                GraphReconciliation(
                    tenantId,
                    checked = 4,
                    discrepancies =
                        listOf(GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "asset", octoId, "no node")),
                )
            },
            GraphTaskOpener { task: Task, _: TaskProvenance -> opened += task; opened(task) },
            null,
        )

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(
                GraphReconciliationController::class.java,
                Supplier { GraphReconciliationController(runner, PlatformAdmin(platformAdmin.toString())) },
            ).withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    @Test
    fun `a platform admin gets the live report and the discrepancy's task opens`() {
        run { mvc ->
            mvc
                .perform(
                    get("/api/v1/admin/graph/reconciliation")
                        .param("tenantId", tenantId.toString())
                        .with(jwt().jwt { it.subject(platformAdmin.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.clean").value(false))
                .andExpect(jsonPath("$.checked").value(4))
                .andExpect(jsonPath("$.discrepancies[0].kind").value("missing"))
                .andExpect(jsonPath("$.discrepancies[0].octoId").value(octoId.toString()))
            assertThat(opened.single().subjectId).isEqualTo("$tenantId:missing:asset:$octoId")
        }
    }

    @Test
    fun `tenant callers and anonymous callers are denied`() {
        run { mvc ->
            mvc
                .perform(
                    get("/api/v1/admin/graph/reconciliation")
                        .param("tenantId", tenantId.toString())
                        .with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isForbidden)
            mvc
                .perform(get("/api/v1/admin/graph/reconciliation").param("tenantId", tenantId.toString()))
                .andExpect(status().isForbidden)
            assertThat(opened).isEmpty()
        }
    }
}
