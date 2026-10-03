package com.octo.api.report

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.workflow.report.ReportSchedules
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import java.util.function.Supplier

/** `/api/v1/report-schedules` with an in-memory store: roles, cron validation, tenant scoping, immutable tenant. */
class ReportScheduleEndpointTest {
    private val analyst = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val outsider = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val schedules = FakeReportSchedules()

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
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
            ).withBean(ReportSchedules::class.java, Supplier { schedules }, { it.isPrimary = true })
            .withPropertyValues(
                "octo.reports.poll=false",
                "octo.reports.schedules.poll=false",
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun body(
        cron: String = "0 0 8 * * MON",
        active: Boolean = true,
    ) = """{"tenantId": "$tenantId", "name": "LP weekly", "type": "performance", "positionSourceType": "fund",
            "positionSourceId": "fund-1", "measures": ["tvpi"], "parameters": {}, "cron": "$cron", "active": $active}"""

    @Test
    fun `an analyst creates reads and lists a schedule while a viewer cannot write`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/report-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("LP weekly"))
                .andExpect(jsonPath("$.type").value("performance"))
                .andExpect(jsonPath("$.active").value(true))

            val id = schedules.schedules.keys.single()
            mvc
                .perform(get("/api/v1/report-schedules/$id").with(jwt().jwt { it.subject(analyst.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(id.toString()))
            mvc
                .perform(get("/api/v1/report-schedules?tenantId=$tenantId").with(jwt().jwt { it.subject(viewer.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
            mvc
                .perform(
                    post("/api/v1/report-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `a bad cron or unknown type is 400 and a non-member sees 404`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/report-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(cron = "not a cron"))
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/report-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("performance", "alchemy"))
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isBadRequest)
            // On-demand only types (#488): report_schedule's type check would reject them at insert.
            for (onDemand in listOf("gl-export", "lp-report")) {
                mvc
                    .perform(
                        post("/api/v1/report-schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body().replace("performance", onDemand))
                            .with(jwt().jwt { it.subject(analyst.toString()) }),
                    ).andExpect(status().isBadRequest)
            }
            mvc
                .perform(
                    post("/api/v1/report-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .with(jwt().jwt { it.subject(outsider.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    get("/api/v1/report-schedules/${UUID.randomUUID()}")
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `update edits the schedule but cannot move it to another tenant`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/report-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body())
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isOk)
            val id = schedules.schedules.keys.single()

            mvc
                .perform(
                    put("/api/v1/report-schedules/$id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(active = false).replace("LP weekly", "LP monthly"))
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("LP monthly"))
                .andExpect(jsonPath("$.active").value(false))
            mvc
                .perform(
                    put("/api/v1/report-schedules/$id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace(tenantId.toString(), UUID.randomUUID().toString()))
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `oversized parameters, too many or too long measures and an over-long position source are 400`() {
        val big = "x".repeat(MAX_REPORT_FIELD_LENGTH + 1)
        val many = (0..MAX_REPORT_MEASURES).joinToString { "\"m$it\"" }
        run { mvc ->
            for (oversized in listOf(
                body().replace("\"parameters\": {}", "\"parameters\": {\"p\": \"${"x".repeat(33_000)}\"}"),
                body().replace("[\"tvpi\"]", "[$many]"),
                body().replace("\"tvpi\"", "\"$big\""),
                body().replace("\"fund-1\"", "\"$big\""),
            )) {
                mvc
                    .perform(
                        post("/api/v1/report-schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(oversized)
                            .with(jwt().jwt { it.subject(analyst.toString()) }),
                    ).andExpect(status().isBadRequest)
            }
            assertThat(schedules.schedules).isEmpty()
        }
    }

    @Test
    fun `a schedule name at the limit is stored and one character over is refused`() {
        val atLimit = "n".repeat(MAX_SCHEDULE_NAME_LENGTH)
        val over = "n".repeat(MAX_SCHEDULE_NAME_LENGTH + 1)
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/report-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("LP weekly", over))
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isBadRequest)
            assertThat(schedules.schedules).isEmpty()
            mvc
                .perform(
                    post("/api/v1/report-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("LP weekly", atLimit))
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value(atLimit))
            assertThat(schedules.schedules.values.single().name).hasSize(MAX_SCHEDULE_NAME_LENGTH)
        }
    }
}
