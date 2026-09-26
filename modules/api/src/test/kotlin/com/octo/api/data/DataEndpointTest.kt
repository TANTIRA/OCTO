package com.mesta.asset.api.data

import com.mesta.asset.api.MestaAssetApplication
import com.mesta.asset.api.access.TenantAccess
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.ingestion.persistence.Observation
import com.mesta.asset.ingestion.persistence.TimeSeriesQuery
import com.mesta.asset.ingestion.persistence.TimeSeriesReader
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.function.Supplier

/** `GET /api/v1/data/{datasetId}` end to end with stubs: a member reads it with the query passed through, everyone else sees 404. */
class DataEndpointTest {
    private val member = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val datasetId = UUID.randomUUID()
    private var lastQuery: TimeSeriesQuery? = null
    private val reader =
        object : TimeSeriesReader {
            override fun datasetTenant(datasetId: UUID) = tenantId.takeIf { datasetId == this@DataEndpointTest.datasetId }

            override fun query(query: TimeSeriesQuery): List<Observation> {
                lastQuery = query
                return listOf(
                    Observation(
                        datasetId,
                        "fund-1",
                        "nav",
                        LocalDate.parse("2026-06-30"),
                        BigDecimal("101"),
                        Instant.parse("2026-07-01T00:00:00Z"),
                    ),
                )
            }
        }

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(MestaAssetApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier {
                    TenantDirectory { id ->
                        if (id ==
                            member
                        ) {
                            listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
                        } else {
                            emptyList()
                        }
                    }
                },
                { it.isPrimary = true },
            ).withBean(TimeSeriesReader::class.java, Supplier { reader }, { it.isPrimary = true })
            .withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private val window = "startDate=2026-01-01&endDate=2026-06-30"

    @Test
    fun `a member reads the dataset and every query parameter reaches the reader`() {
        run { mvc ->
            mvc
                .perform(
                    get(
                        "/api/v1/data/$datasetId?$window&fields=nav,irr&asOfTime=2026-07-02T00:00:00Z&since=2026-06-01T00:00:00Z",
                    ).with(
                        jwt().jwt {
                            it.subject(member.toString())
                        },
                    ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.datasetId").value(datasetId.toString()))
                .andExpect(jsonPath("$.observations[0].field").value("nav"))
                .andExpect(jsonPath("$.observations[0].value").value(101))
                .andExpect(jsonPath("$.observations[0].recordedAt").value("2026-07-01T00:00:00Z"))
            val q = lastQuery!!
            assert(q.fields == setOf("nav", "irr")) { q }
            assert(q.asOfTime == Instant.parse("2026-07-02T00:00:00Z")) { q }
            assert(q.since == Instant.parse("2026-06-01T00:00:00Z")) { q }
        }
    }

    @Test
    fun `a non-member, an unknown dataset and a reversed window are refused`() {
        run { mvc ->
            mvc
                .perform(
                    get("/api/v1/data/$datasetId?$window").with(
                        jwt().jwt {
                            it.subject(UUID.randomUUID().toString())
                        },
                    ),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    get("/api/v1/data/${UUID.randomUUID()}?$window").with(
                        jwt().jwt {
                            it.subject(member.toString())
                        },
                    ),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    get("/api/v1/data/$datasetId?startDate=2026-06-30&endDate=2026-01-01").with(
                        jwt().jwt {
                            it.subject(member.toString())
                        },
                    ),
                ).andExpect(status().isBadRequest)
            mvc.perform(get("/api/v1/data/$datasetId?$window")).andExpect(status().isForbidden)
        }
    }
}
