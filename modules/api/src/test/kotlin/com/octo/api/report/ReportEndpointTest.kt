package com.octo.api.report

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.persistence.TenantScope
import com.octo.workflow.report.PENDING_REPORT_LIMIT
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportRequest
import com.octo.workflow.report.ReportType
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
    private val approver = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val jobs = FakeReportJobs()

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier {
                    TenantDirectory { id ->
                        when (id) {
                            analyst -> listOf(TenantAccess(tenantId, "acme", TenantRole.ANALYST))
                            approver -> listOf(TenantAccess(tenantId, "acme", TenantRole.APPROVER))
                            viewer -> listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
                            else -> emptyList()
                        }
                    }
                },
                { it.isPrimary = true },
            ).withBean(ReportJobs::class.java, Supplier { jobs }, { it.isPrimary = true })
            .withPropertyValues(
                "octo.reports.poll=false",
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
    fun `an analyst queues a job and reads it back, the parameters reach the store as json, the draft stays sealed`() {
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

            jobs.claimNext()!!.let { jobs.complete(it.id, it.claimToken!!, """{"tvpi": 1.3}""") }
            mvc
                .perform(get("/api/v1/reports/$id").with(jwt().jwt { it.subject(viewer.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("done"))
                // Unreleased: the draft and its hash stay behind the release gate on the status read too (#482).
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.artifactSha256").doesNotExist())
                // The queue reads the gate's state off the job view (#490).
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.approvalTaskId").doesNotExist())
                .andExpect(jsonPath("$.taskStatus").doesNotExist())
            // An approver reads the sealed draft so they can decide the release (#552).
            mvc
                .perform(get("/api/v1/reports/$id").with(jwt().jwt { it.subject(approver.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.result.tvpi").value(1.3))
        }
    }

    @Test
    fun `a full queue refuses submissions until a job finishes`() {
        run { mvc ->
            repeat(PENDING_REPORT_LIMIT) {
                jobs.submit(
                    ReportRequest(
                        tenantId,
                        ReportType.PERFORMANCE,
                        "inline-series",
                        "fund-1",
                        listOf("tvpi"),
                        "{}",
                        "test",
                        UUID.randomUUID(),
                    ),
                    TenantScope.All,
                )
            }
            mvc.perform(post(analyst)).andExpect(status().isTooManyRequests)

            jobs.claimNext()!!.let { jobs.complete(it.id, it.claimToken!!, "{}") }
            mvc.perform(post(analyst)).andExpect(status().isAccepted)
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

    @Test
    fun `oversized parameters, too many or too long measures and an over-long position source are 400`() {
        val big = "x".repeat(MAX_REPORT_FIELD_LENGTH + 1)
        val many = (0..MAX_REPORT_MEASURES).joinToString { "\"m$it\"" }
        run { mvc ->
            for (oversized in listOf(
                body().replace("\"USD\"", "\"${"x".repeat(33_000)}\""),
                body().replace("[\"tvpi\"]", "[$many]"),
                body().replace("\"tvpi\"", "\"$big\""),
                body().replace("\"fund-1\"", "\"$big\""),
                // #487: a whitespace-only measure is a validation error, not a 500 from the request rule.
                body().replace("\"tvpi\"", "\" \""),
            )) {
                mvc.perform(post(analyst, oversized)).andExpect(status().isBadRequest)
            }
            assertThat(jobs.jobs).isEmpty()
        }
    }

    @Test
    fun `a whitespace-only measure is a validation error`() {
        run { mvc ->
            mvc.perform(post(analyst, body().replace("[\"tvpi\"]", "[\" \"]"))).andExpect(status().isBadRequest)
            mvc.perform(post(analyst, body().replace("[\"tvpi\"]", "[\"tvpi\", \" \"]"))).andExpect(status().isBadRequest)
            assertThat(jobs.jobs).isEmpty()
        }
    }
}
