package com.mesta.asset.api.report

import com.mesta.asset.api.MestaAssetApplication
import com.mesta.asset.api.access.TenantAccess
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.workflow.Task
import com.mesta.asset.workflow.TaskEvent
import com.mesta.asset.workflow.TaskState
import com.mesta.asset.workflow.next
import com.mesta.asset.workflow.opened
import com.mesta.asset.workflow.persistence.TaskProvenance
import com.mesta.asset.workflow.report.ReportJobs
import com.mesta.asset.workflow.report.ReportRequest
import com.mesta.asset.workflow.report.ReportType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/** The release gate end to end with in-memory stores: a task is opened once for a done job, and the result shows only after approval. */
class ReleaseEndpointTest {
    private val analyst = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val jobs = FakeReportJobs()
    private val taskStates = mutableMapOf<UUID, TaskState>()
    private val tasks =
        object : ReleaseTasks {
            override fun open(
                task: Task,
                provenance: TaskProvenance,
            ) {
                taskStates[task.id] = opened(task)
            }

            override fun state(taskId: UUID) = taskStates[taskId]
        }

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
            .withBean(ReleaseTasks::class.java, Supplier { tasks }, { it.isPrimary = true })
            .withPropertyValues(
                "mesta.reports.poll=false",
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun doneJob(): UUID {
        val job =
            jobs.submit(
                ReportRequest(
                    tenantId,
                    ReportType.PERFORMANCE,
                    "inline-series",
                    "fund-1",
                    listOf("tvpi"),
                    "{}",
                    analyst.toString(),
                    UUID.randomUUID(),
                ),
            )
        jobs.claimNext()
        jobs.complete(job.id, """{"tvpi": 1.3}""", "a".repeat(64))
        return job.id
    }

    private fun asUser(id: UUID) = jwt().jwt { it.subject(id.toString()) }

    @Test
    fun `release is requested once for a done job and the result appears only after approval`() {
        run { mvc ->
            val id = doneJob()
            mvc
                .perform(get("/api/v1/reports/$id/release").with(asUser(viewer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.approvalTaskId").doesNotExist())
            mvc
                .perform(post("/api/v1/reports/$id/release").with(asUser(analyst)))
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.taskStatus").value("open"))
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.result").doesNotExist())
            mvc.perform(post("/api/v1/reports/$id/release").with(asUser(analyst))).andExpect(status().isConflict)

            val taskId = jobs.load(id)!!.approvalTaskId!!
            assertThat(taskStates.getValue(taskId).task.requestedBy).isEqualTo(analyst.toString())
            taskStates[taskId] = taskStates.getValue(taskId).next(TaskEvent.Approved("approver-1", Instant.now()))
            mvc
                .perform(get("/api/v1/reports/$id/release").with(asUser(viewer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.taskStatus").value("approved"))
                .andExpect(jsonPath("$.result.tvpi").value(1.3))
                .andExpect(jsonPath("$.artifactSha256").value("a".repeat(64)))
        }
    }

    @Test
    fun `a viewer, a non-member, an unfinished job and an unknown id cannot open the gate`() {
        run { mvc ->
            val done = doneJob()
            mvc.perform(post("/api/v1/reports/$done/release").with(asUser(viewer))).andExpect(status().isNotFound)
            mvc.perform(post("/api/v1/reports/$done/release").with(asUser(UUID.randomUUID()))).andExpect(status().isNotFound)
            val queued =
                jobs
                    .submit(
                        ReportRequest(
                            tenantId,
                            ReportType.PERFORMANCE,
                            "inline-series",
                            "fund-1",
                            emptyList(),
                            "{}",
                            analyst.toString(),
                            UUID.randomUUID(),
                        ),
                    ).id
            mvc.perform(post("/api/v1/reports/$queued/release").with(asUser(analyst))).andExpect(status().isConflict)
            mvc.perform(get("/api/v1/reports/${UUID.randomUUID()}/release").with(asUser(analyst))).andExpect(status().isNotFound)
            mvc.perform(post("/api/v1/reports/$done/release")).andExpect(status().isForbidden)
            assertThat(taskStates).isEmpty()
        }
    }
}
