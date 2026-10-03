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
import testsupport.graph.GraphReconciliationEndpointWiring
import java.util.UUID

/**
 * `GET /api/v1/admin/graph/reconciliation` gated the same way tenant provisioning is: a platform
 * admin gets the live report — which also opens the drift tasks — and every other caller is
 * denied (#564). `NEO4J_URI` stays unset so the conditional graph beans (and the scanned
 * controller) stay out; the endpoint is supplied by [GraphReconciliationEndpointWiring] with a
 * planted runner, which also exercises that the route maps from a bean-registered controller. The
 * wiring class lives outside `com.octo.api` precisely so this harness's component scan cannot pull
 * it into unrelated OctoApplication contexts.
 */
class GraphReconciliationEndpointTest {
    private fun run(block: (MockMvc, GraphReconciliationEndpointWiring) -> Unit) {
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java, GraphReconciliationEndpointWiring::class.java)
            .withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            ).run { context ->
                block(
                    MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build(),
                    context.getBean(GraphReconciliationEndpointWiring::class.java),
                )
            }
    }

    @Test
    fun `a platform admin gets the live report and the discrepancy's task opens`() {
        run { mvc, wiring ->
            mvc
                .perform(
                    get("/api/v1/admin/graph/reconciliation")
                        .param("tenantId", wiring.tenantId.toString())
                        .with(jwt().jwt { it.subject(wiring.platformAdmin.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.clean").value(false))
                .andExpect(jsonPath("$.checked").value(4))
                .andExpect(jsonPath("$.discrepancies[0].kind").value("missing"))
                .andExpect(jsonPath("$.discrepancies[0].octoId").value(wiring.octoId.toString()))
            assertThat(wiring.opened.single().subjectId)
                .isEqualTo("${wiring.tenantId}:missing:asset:${wiring.octoId}")
        }
    }

    @Test
    fun `tenant callers and anonymous callers are denied`() {
        run { mvc, wiring ->
            mvc
                .perform(
                    get("/api/v1/admin/graph/reconciliation")
                        .param("tenantId", wiring.tenantId.toString())
                        .with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isForbidden)
            mvc
                .perform(get("/api/v1/admin/graph/reconciliation").param("tenantId", wiring.tenantId.toString()))
                .andExpect(status().isForbidden)
            assertThat(wiring.opened).isEmpty()
        }
    }
}
