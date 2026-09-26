package com.mesta.asset.api.report

import com.mesta.asset.api.MestaAssetApplication
import com.mesta.asset.api.access.TenantAccess
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.workflow.report.ReportJobs
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

/** `POST /api/v1/reports` and `GET /api/v1/reports/{id}` with an in-memory store: roles, tenant scoping, and the job view. */
class ReportEndpointTest {
    private val analyst = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val jobs = FakeReportJobs()

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
            ).withBean(ReportJobs::class.java, Supplier { jobs }, { it.isPrimary = true })
            .withPropertyValues(
                "mesta.reports.poll=false",
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun body(type: String = "performance") =
        """{"tenantId": "$tenantId", "type": "$type", "positionSourceType": "inline-series", "positionSourceId": "fund-1",
            "measures": ["tvpi"], "parameters": {"currency": "USD"}}"""

    private fun post(
        subject: UUID,
        json: String = body(),
    ) = post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(json).with(jwt().jwt { it.subject(subject.toString()) })

    @Test
    fun `an analyst queues a job and reads it back, and the parameters reach the store as json`() {
        run { mvc ->
            val location =
                mvc
                    .perform(post(analyst))
                    .andExpect(status().isAccepted)
                    .andExpect(jsonPath("$.status").value("new"))
                    .andExpect(jsonPath("$.type").value("performance"))
                    .andExpect(jsonPath("$.measures[0]").value("tvpi"))
                    .andReturn()
                    .response.contentAsString
            val id = jobs.jobs.keys.single()
            assertThat(location).contains(id.toString())
            assertThat(
                jobs.jobs
                    .getValue(id)
                    .request.parameters,
            ).contains("\"currency\"")
            assertThat(
                jobs.jobs
                    .getValue(id)
                    .request.requestedBy,
            ).isEqualTo(analyst.toString())

            jobs.complete(jobs.claimNext()!!.id, """{"tvpi": 1.3}""")
            mvc
                .perform(get("/api/v1/reports/$id").with(jwt().jwt { it.subject(viewer.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.result.tvpi").value(1.3))
        }
    }

    @Test
    fun `a viewer, a non-member, a bad type and a missing token are refused`() {
        run { mvc ->
            mvc.perform(post(viewer)).andExpect(status().isNotFound)
            mvc.perform(post(UUID.randomUUID())).andExpect(status().isNotFound)
            mvc.perform(post(analyst, body(type = "risk"))).andExpect(status().isBadRequest)
            mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden)
            mvc
                .perform(
                    get("/api/v1/reports/${UUID.randomUUID()}").with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isNotFound)
            assertThat(jobs.jobs).isEmpty()
        }
    }
}
