package com.octo.api.graph

import com.octo.api.OctoApplication
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

/** `GET /api/v1/admin/graph/reconciliation` is platform-admin only and refuses to invent a report when no graph is wired. */
class GraphReconciliationEndpointTest {
    private val platformAdmin = UUID.randomUUID()
    private val outsider = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val octoId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()
    private val calls = mutableListOf<UUID>()

    private val runs =
        GraphReconciliationRuns { id, _ ->
            calls += id
            val discrepancy = GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "asset", octoId, "no node")
            GraphReconciliationReport(
                GraphReconciliation(id, 2, listOf(discrepancy)),
                listOf(GraphDriftTask(discrepancy, taskId, opened = true)),
            )
        }
    private val tenants =
        object : GraphTenantDirectory {
            override fun ids() = listOf(tenantId)

            override fun exists(tenantId: UUID) = tenantId == this@GraphReconciliationEndpointTest.tenantId
        }

    private val withGraph =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(GraphReconciliationRuns::class.java, Supplier { runs })
            .withBean(GraphTenantDirectory::class.java, Supplier { tenants })
            .withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
                "NEO4J_URI=",
                "OCTO_PLATFORM_ADMINS=$platformAdmin",
            )

    private val withoutGraph =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
                "NEO4J_URI=",
                "OCTO_PLATFORM_ADMINS=$platformAdmin",
            )

    private fun WebApplicationContextRunner.mvc(block: (MockMvc) -> Unit) {
        run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun MockMvc.getReconciliation(
        caller: UUID?,
        tenant: UUID = tenantId,
    ) = perform(
        get("/api/v1/admin/graph/reconciliation")
            .param("tenantId", tenant.toString())
            .let { if (caller == null) it else it.with(jwt().jwt { it.subject(caller.toString()) }) },
    )

    @Test
    fun `a platform admin gets the tenant report and its drift task`() {
        calls.clear()
        withGraph.mvc { mvc ->
            mvc
                .getReconciliation(platformAdmin)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.checked").value(2))
                .andExpect(jsonPath("$.clean").value(false))
                .andExpect(jsonPath("$.discrepancies[0].kind").value("missing"))
                .andExpect(jsonPath("$.discrepancies[0].aggregateType").value("asset"))
                .andExpect(jsonPath("$.discrepancies[0].octoId").value(octoId.toString()))
                .andExpect(jsonPath("$.discrepancies[0].detail").value("no node"))
                .andExpect(jsonPath("$.discrepancies[0].taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.discrepancies[0].opened").value(true))
            assertThat(calls).containsExactly(tenantId)
        }
    }

    @Test
    fun `anyone else is forbidden and does not run reconciliation`() {
        calls.clear()
        withGraph.mvc { mvc ->
            mvc.getReconciliation(outsider).andExpect(status().isForbidden)
            mvc.getReconciliation(null).andExpect(status().isForbidden)
            mvc
                .perform(get("/api/v1/admin/graph/reconciliation").with(jwt().jwt { it.subject(platformAdmin.toString()) }))
                .andExpect(status().isBadRequest)
            assertThat(calls).isEmpty()
        }
    }

    @Test
    fun `an unknown tenant is 404 and does not open a task`() {
        calls.clear()
        withGraph.mvc { mvc ->
            mvc.getReconciliation(platformAdmin, UUID.randomUUID()).andExpect(status().isNotFound)
            assertThat(calls).isEmpty()
        }
    }

    @Test
    fun `without a graph the route answers 503`() {
        withoutGraph.mvc { mvc ->
            mvc.getReconciliation(platformAdmin).andExpect(status().isServiceUnavailable)
        }
    }
}
