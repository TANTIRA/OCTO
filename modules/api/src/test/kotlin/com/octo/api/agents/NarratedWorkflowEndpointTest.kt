package com.octo.api.agents

import com.octo.analytics.BridgeDriver
import com.octo.analytics.BridgeMethod
import com.octo.analytics.BridgePoint
import com.octo.analytics.valueBridge
import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import java.util.function.Supplier

/**
 * The three sealed narrators the sidecar already exposed: each route calls that workflow with
 * the platform's payload. Equity-bridge figures are the ones [valueBridge] computes, not a
 * pass-through of caller-supplied effects.
 */
class NarratedWorkflowEndpointTest {
    private val member = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()

    private var calls = 0
    private var behavior: (String, Map<String, Any>) -> Map<String, Any> = { workflow, payload ->
        calls += 1
        seen = workflow to payload
        mapOf("status" to "completed", "analysis" to "rose", "response" to "answered", "review" to "on track")
    }
    private var seen: Pair<String, Map<String, Any>>? = null
    private val agents = AgentsClient { workflow, payload -> behavior(workflow, payload) }

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

    @Test
    fun `equity-bridge sends the computed bridge to the equity-bridge workflow`() {
        run { mvc ->
            val expected =
                valueBridge(
                    point("2019-06-30", "100", "0.20", "8", "60", "1.0"),
                    point("2024-06-30", "150", "0.25", "10", "40", "1.1"),
                    Currency.getInstance("EUR"),
                    Currency.getInstance("USD"),
                    BridgeMethod.Shapley,
                )
            mvc
                .perform(
                    post("/api/v1/analytics/equity-bridge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bridgeBody(method = "shapley"))
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.computed.change").value(expected.change.toPlainString()))
                .andExpect(jsonPath("$.computed.method").value("shapley"))
                .andExpect(jsonPath("$.computed.methodology").value("quantitative-methodology §4.3 v1"))
            assertThat(seen?.first).isEqualTo("equity-bridge")
            assertThat(seen?.second?.get("effects")).isEqualTo(
                expected.effects.mapKeys { it.key.name }.mapValues { it.value.toPlainString() },
            )
            assertThat(seen?.second?.get("change")).isEqualTo(expected.change.toPlainString())
            assertThat(seen?.second?.get("entry")).isEqualTo(
                mapOf(
                    "date" to "2019-06-30",
                    "revenue" to "100",
                    "margin" to "0.20",
                    "multiple" to "8",
                    "net_debt" to "60",
                    "fx_rate" to "1.0",
                ),
            )
            assertThat(seen?.second?.get("local_currency")).isEqualTo("EUR")
            assertThat(seen?.second?.get("reporting_currency")).isEqualTo("USD")
            assertThat(seen?.second?.get("tenant_id")).isEqualTo(tenantId.toString())
            assertThat((seen!!.second.getValue("effects") as Map<*, *>).keys.map { it.toString() })
                .containsExactlyInAnyOrderElementsOf(BridgeDriver.entries.map { it.name })

            seen = null
            mvc
                .perform(
                    post("/api/v1/analytics/equity-bridge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            bridgeBody(
                                method = "sequential",
                                ordering = """["REVENUE","MARGIN","MULTIPLE","NET_DEBT","FX"]""",
                            ),
                        ).with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.computed.method").value("sequential:REVENUE,MARGIN,MULTIPLE,NET_DEBT,FX"))
            assertThat(seen?.first).isEqualTo("equity-bridge")
            assertThat(seen?.second?.get("method")).isEqualTo("sequential:REVENUE,MARGIN,MULTIPLE,NET_DEBT,FX")
        }
    }

    @Test
    fun `equity-bridge refuses a viewer, an outsider, a bad bridge, and an anonymous call without calling`() {
        run { mvc ->
            mvc.postBridge(viewer).andExpect(status().isNotFound)
            mvc.postBridge(UUID.randomUUID()).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/analytics/equity-bridge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bridgeBody(method = "shapley")),
                ).andExpect(status().isForbidden)
            mvc
                .perform(
                    post("/api/v1/analytics/equity-bridge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bridgeBody(method = "shapley", entryDate = "2024-06-30", exitDate = "2019-06-30"))
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/analytics/equity-bridge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bridgeBody(method = "sequential", ordering = """["REVENUE"]"""))
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            assertThat(seen).isNull()
        }
    }

    @Test
    fun `ddq-response and operating-review call those workflows and reject empty input`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/fundraising/ddq-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","subject":"q3-ddq","questions":["Vintage?"],"facts":{"vintage":"2019"}}""",
                        ).with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.response").value("answered"))
            assertThat(seen?.first).isEqualTo("ddq-response")
            assertThat(seen?.second?.get("subject")).isEqualTo("q3-ddq")
            assertThat(seen?.second?.get("questions")).isEqualTo(listOf("Vintage?"))
            assertThat(seen?.second?.get("facts")).isEqualTo(mapOf("vintage" to "2019"))

            val afterDdq = calls
            mvc
                .perform(
                    post("/api/v1/fundraising/ddq-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","subject":"q3-ddq","questions":["  "],"facts":{}}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            assertThat(calls).isEqualTo(afterDdq)
            mvc
                .perform(
                    post("/api/v1/fundraising/ddq-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","subject":"q3-ddq","questions":["Vintage?"],"facts":{}}""")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)

            mvc
                .perform(
                    post("/api/v1/portfolio/operating-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","company":"Acme","levers":["pricing"],"metrics":{"2026-Q1":{"revenue":"10"}}}""",
                        ).with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.review").value("on track"))
            assertThat(seen?.first).isEqualTo("operating-review")
            assertThat(seen?.second?.get("company")).isEqualTo("Acme")
            assertThat(seen?.second?.get("levers")).isEqualTo(listOf("pricing"))
            assertThat((seen?.second?.get("metrics") as Map<*, *>)["2026-Q1"]).isEqualTo(mapOf("revenue" to "10"))

            val afterReview = calls
            mvc
                .perform(
                    post("/api/v1/portfolio/operating-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","company":"Acme","levers":["pricing"],"metrics":{}}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            assertThat(calls).isEqualTo(afterReview)
        }
    }

    @Test
    fun `a down sidecar is 503 and its own failure is 502`() {
        run { mvc ->
            behavior = { _, _ -> throw AgentsUnavailableException(IOException("down")) }
            mvc.postBridge(member).andExpect(status().isServiceUnavailable)
            behavior = { _, _ -> throw AgentsCallException(503, "flag off") }
            mvc.postBridge(member).andExpect(status().isServiceUnavailable)
            behavior = { _, _ -> throw AgentsCallException(500, "registry") }
            mvc.postBridge(member).andExpect(status().isBadGateway)

            behavior = { _, _ -> throw AgentsCallException(500, "registry") }
            mvc
                .perform(
                    post("/api/v1/fundraising/ddq-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","subject":"q3-ddq","questions":["Vintage?"],"facts":{}}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadGateway)
            mvc
                .perform(
                    post("/api/v1/portfolio/operating-review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","company":"Acme","levers":["pricing"],"metrics":{"q":{"revenue":"1"}}}""",
                        ).with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadGateway)
        }
    }

    private fun MockMvc.postBridge(caller: UUID) =
        perform(
            post("/api/v1/analytics/equity-bridge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bridgeBody(method = "shapley"))
                .with(jwt().jwt { it.subject(caller.toString()) }),
        )

    private fun bridgeBody(
        method: String,
        ordering: String = "[]",
        entryDate: String = "2019-06-30",
        exitDate: String = "2024-06-30",
    ) = """
        {
          "tenantId":"$tenantId",
          "company":"Acme",
          "localCurrency":"EUR",
          "reportingCurrency":"USD",
          "method":"$method",
          "ordering":$ordering,
          "entry":{"date":"$entryDate","revenue":"100","margin":"0.20","multiple":"8","netDebt":"60","fxRate":"1.0"},
          "exit":{"date":"$exitDate","revenue":"150","margin":"0.25","multiple":"10","netDebt":"40","fxRate":"1.1"}
        }
        """.trimIndent()

    private fun point(
        date: String,
        revenue: String,
        margin: String,
        multiple: String,
        netDebt: String,
        fx: String,
    ) = BridgePoint(
        LocalDate.parse(date),
        BigDecimal(revenue),
        BigDecimal(margin),
        BigDecimal(multiple),
        BigDecimal(netDebt),
        BigDecimal(fx),
    )
}
