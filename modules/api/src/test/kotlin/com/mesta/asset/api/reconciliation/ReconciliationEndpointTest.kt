package com.mesta.asset.api.reconciliation

import com.mesta.asset.api.MestaAssetApplication
import com.mesta.asset.api.access.TenantAccess
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.recon.matching.IborRecord
import com.mesta.asset.recon.matching.persistence.ReconciliationStore
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import java.util.function.Supplier

/** `POST /api/v1/reconciliations` end to end with an in-memory store and ledger: roles, the run view, and the refusals. */
class ReconciliationEndpointTest {
    private val analyst = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val usd = Currency.getInstance("USD")
    private val day = LocalDate.parse("2026-06-30")
    private val kept = IborRecord(UUID.randomUUID(), "admin-a", "t-1", BigDecimal("-100"), usd, day)
    private val store = FakeReconciliationStore(listOf(kept))
    private val opened = mutableListOf<com.mesta.asset.workflow.Task>()

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(MestaAssetApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier {
                    TenantDirectory { id ->
                        when (id) {
                            analyst -> listOf(TenantAccess(tenantId, "acme", TenantRole.ANALYST))
                            viewer -> listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
                            else -> emptyList()
                        }
                    }
                },
                { it.isPrimary = true },
            ).withBean(ReconciliationStore::class.java, Supplier { store }, { it.isPrimary = true })
            .withBean(BreakTaskOpener::class.java, Supplier { BreakTaskOpener { task, _ -> opened += task } }, { it.isPrimary = true })
            .withPropertyValues(
                "mesta.reports.poll=false",
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun body(vararg records: String) =
        """{"tenantId": "$tenantId", "tolerance": {"amount": "0.5", "days": 0}, "records": [${records.joinToString(",")}]}"""

    private fun record(
        id: String,
        amount: String,
    ) = """{"sourceSystem": "admin-a", "externalId": "$id", "amount": "$amount", "currency": "USD", "date": "2026-06-30"}"""

    private fun post(
        subject: UUID,
        json: String,
    ) = post(
        "/api/v1/reconciliations",
    ).contentType(MediaType.APPLICATION_JSON).content(json).with(jwt().jwt { it.subject(subject.toString()) })

    @Test
    fun `an analyst's run returns the match count and each break with its task`() {
        run { mvc ->
            mvc
                .perform(post(analyst, body(record("t-1", "-100.4"), record("t-2", "40"))))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.breaks.length()").value(1))
                .andExpect(jsonPath("$.breaks[0].kind").value("missing-in-ibor"))
                .andExpect(jsonPath("$.breaks[0].sourceRef").value("t-2"))
                .andExpect(jsonPath("$.breaks[0].taskId").isNotEmpty)
                .andExpect(jsonPath("$.breaks[0].opened").value(true))
            assertThat(store.rows).hasSize(1)
            assertThat(opened.single().requestedBy).isEqualTo(analyst.toString())
        }
    }

    @Test
    fun `a viewer, a non-member, duplicate keys, an empty batch and no token are refused`() {
        run { mvc ->
            mvc.perform(post(viewer, body(record("t-1", "-100")))).andExpect(status().isNotFound)
            mvc.perform(post(UUID.randomUUID(), body(record("t-1", "-100")))).andExpect(status().isNotFound)
            mvc.perform(post(analyst, body(record("t-1", "-100"), record("t-1", "-100")))).andExpect(status().isBadRequest)
            mvc.perform(post(analyst, body())).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/reconciliations").contentType(MediaType.APPLICATION_JSON).content(body(record("t-1", "-100"))),
                ).andExpect(status().isForbidden)
            assertThat(store.rows).isEmpty()
        }
    }
}
