package com.octo.api.report

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.persistence.TenantScope
import com.octo.workflow.Task
import com.octo.workflow.TaskEvent
import com.octo.workflow.TaskState
import com.octo.workflow.next
import com.octo.workflow.opened
import com.octo.workflow.persistence.TaskProvenance
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

/** The release gate end to end with in-memory stores: a task is opened once for a done job, and the result shows only after approval. */
class ReleaseEndpointTest {
    private val analyst = UUID.randomUUID()
    private val approver = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val jobs = FakeReportJobs()
    private val taskStates = mutableMapOf<UUID, TaskState>()
    private var openBehavior: ((Task) -> TaskState)? = null
    private val tasks =
        object : ReleaseTasks {
            override fun openUnlessOpen(
                task: Task,
                provenance: TaskProvenance,
            ): TaskState =
                openBehavior?.invoke(task)
                    ?: taskStates.values.firstOrNull {
                        it.task.subjectType == task.subjectType && it.task.subjectId == task.subjectId &&
                            it.task.kind == task.kind && !it.status.terminal
                    } ?: opened(task).also { taskStates[task.id] = it }

            override fun state(taskId: UUID) = taskStates[taskId]

            override fun append(
                taskId: UUID,
                event: TaskEvent,
                provenance: TaskProvenance,
            ): TaskState {
                val before = taskStates[taskId] ?: throw NoSuchElementException("no task $taskId")
                return before.next(event).also { taskStates[taskId] = it }
            }
        }

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
            .withBean(ReleaseTasks::class.java, Supplier { tasks }, { it.isPrimary = true })
            .withPropertyValues(
                "octo.reports.poll=false",
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
                TenantScope.All,
            )
        val claimed = jobs.claimNext()!!
        jobs.complete(job.id, claimed.claimToken!!, """{"tvpi": 1.3}""", "a".repeat(64))
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
            mvc
                .perform(get("/api/v1/reports/$id").with(asUser(viewer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.artifactSha256").doesNotExist())
                .andExpect(jsonPath("$.taskStatus").value("open"))
                .andExpect(jsonPath("$.released").value(false))
            // The approver reads the sealed draft they are being asked to release (#552).
            mvc
                .perform(get("/api/v1/reports/$id").with(asUser(approver)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.result.tvpi").value(1.3))
            mvc
                .perform(get("/api/v1/reports/$id/release").with(asUser(approver)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.result.tvpi").value(1.3))

            val taskId = jobs.load(id, TenantScope.All)!!.approvalTaskId!!
            assertThat(taskStates.getValue(taskId).task.requestedBy).isEqualTo(analyst.toString())
            mvc
                .perform(
                    post("/api/v1/reports/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event": "approved"}""")
                        .with(asUser(approver)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("approved"))
            mvc
                .perform(get("/api/v1/reports/$id/release").with(asUser(viewer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.taskStatus").value("approved"))
                .andExpect(jsonPath("$.result.tvpi").value(1.3))
                .andExpect(jsonPath("$.artifactSha256").value("a".repeat(64)))
            mvc
                .perform(get("/api/v1/reports/$id").with(asUser(viewer)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.result.tvpi").value(1.3))
                .andExpect(jsonPath("$.artifactSha256").value("a".repeat(64)))
        }
    }

    @Test
    fun `a request losing the attach race reuses the winner's task and answers conflict`() {
        run { mvc ->
            val id = doneJob()
            openBehavior = { task ->
                // The winner deduped to the same subject, opened one task, and attached it between
                // this request's open and its attach — the loser's update finds the slot taken.
                opened(task).also {
                    taskStates[task.id] = it
                    jobs.attachApproval(id, task.id)
                }
            }
            mvc
                .perform(post("/api/v1/reports/$id/release").with(asUser(analyst)))
                .andExpect(status().isConflict)
            assertThat(taskStates).hasSize(1)
            assertThat(jobs.load(id, TenantScope.All)!!.approvalTaskId).isNotNull()
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
                        TenantScope.All,
                    ).id
            mvc.perform(post("/api/v1/reports/$queued/release").with(asUser(analyst))).andExpect(status().isConflict)
            mvc.perform(get("/api/v1/reports/${UUID.randomUUID()}/release").with(asUser(analyst))).andExpect(status().isNotFound)
            mvc.perform(post("/api/v1/reports/$done/release")).andExpect(status().isForbidden)
            assertThat(taskStates).isEmpty()
        }
    }

    @Test
    fun `the release task is decidable only by an approver who did not request it`() {
        run { mvc ->
            val id = doneJob()
            mvc.perform(post("/api/v1/reports/$id/release").with(asUser(analyst))).andExpect(status().isAccepted)
            val taskId = jobs.load(id, TenantScope.All)!!.approvalTaskId!!

            fun decide(
                user: UUID,
                event: String,
            ) = mvc.perform(
                post("/api/v1/reports/$id/tasks/$taskId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(event)
                    .with(asUser(user)),
            )

            // A viewer, a non-approver and the requester are all refused before the machine runs.
            decide(viewer, """{"event": "approved"}""").andExpect(status().isNotFound)
            decide(analyst, """{"event": "approved"}""").andExpect(status().isNotFound)
            // Missing rationale and unknown events are 400 before a decision is attempted.
            decide(approver, """{"event": "rejected"}""").andExpect(status().isBadRequest)
            decide(approver, """{"event": "sideways"}""").andExpect(status().isBadRequest)
            // A task that does not exist — or is not this job's — is 404, never revealed.
            mvc
                .perform(
                    post("/api/v1/reports/$id/tasks/${UUID.randomUUID()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event": "approved"}""")
                        .with(asUser(approver)),
                ).andExpect(status().isNotFound)

            decide(approver, """{"event": "approved", "rationale": "numbers reviewed"}""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.decidedBy").value(approver.toString()))
            // A terminal task accepts nothing more.
            decide(approver, """{"event": "rejected", "rationale": "changed my mind"}""").andExpect(status().isConflict)
        }
    }

    @Test
    fun `an approver who requested the release cannot decide it`() {
        run { mvc ->
            val id = doneJob()
            mvc.perform(post("/api/v1/reports/$id/release").with(asUser(approver))).andExpect(status().isAccepted)
            val taskId = jobs.load(id, TenantScope.All)!!.approvalTaskId!!
            // Segregation of duties lives in the machine: the role check passes, the append throws.
            mvc
                .perform(
                    post("/api/v1/reports/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event": "approved"}""")
                        .with(asUser(approver)),
                ).andExpect(status().isConflict)
        }
    }
}
