package com.octo.api.report

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.TaskEventRequest
import com.octo.api.TaskView
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.isGateDecision
import com.octo.api.taskView
import com.octo.persistence.TenantScope
import com.octo.workflow.Task
import com.octo.workflow.TaskEvent
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.TaskStatus
import com.octo.workflow.persistence.TaskProvenance
import com.octo.workflow.report.JobStatus
import com.octo.workflow.report.ReportJob
import com.octo.workflow.report.ReportJobs
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** The workflow tasks the release gate opens, reads, and decides; `JdbcTaskStore` behind it in production. */
interface ReleaseTasks {
    /** The subject's already-open task of this kind, or [task] freshly opened — two racers never mint two. */
    fun openUnlessOpen(
        task: Task,
        provenance: TaskProvenance,
    ): TaskState

    fun state(taskId: UUID): TaskState?

    /** Applies [event] to the task through its state machine — the same validation `JdbcTaskStore.append` gets. */
    fun append(
        taskId: UUID,
        event: TaskEvent,
        provenance: TaskProvenance,
    ): TaskState
}

/**
 * Whether [task] — a report job's approval task — releases the job's artifact. Every read path that could
 * carry a result or its hash gates on this one check (#482), so a draft never leaks around the gate.
 */
internal fun releases(task: TaskState?): Boolean = task?.status == TaskStatus.APPROVED

/**
 * The approval gate of #6 slice 7: an outbound artifact passes a `workflow_task` of kind `approval` before
 * release. `POST /api/v1/reports/{id}/release` opens that task for a `done` job, once; `GET …/release` tells
 * whether the task is approved and only then carries the result. Segregation of duties (V5, V7): the
 * requester of the task cannot be the one who approves it.
 */
@RestController
class ReleaseController(
    private val jobs: ReportJobs,
    private val tasks: ReleaseTasks,
    private val tenants: TenantDirectory,
    private val json: ObjectMapper,
) {
    @PostMapping("/api/v1/reports/{id}/release")
    fun requestRelease(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ReleaseView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val job = jobs.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, job) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (job.status != JobStatus.DONE || job.approvalTaskId != null) return ResponseEntity.status(HttpStatus.CONFLICT).build()
        val task = Task(UUID.randomUUID(), TaskKind.APPROVAL, "report-job", id.toString(), jwt.subject!!, Instant.now())
        // openUnlessOpen dedupes on the job subject, so a loser adopts the winner's task instead of
        // orphaning one nobody can decide; its attach then finds the slot taken and is a 409.
        val openedTask = tasks.openUnlessOpen(task, TaskProvenance("api", job.request.correlationId))
        val attached =
            try {
                jobs.attachApproval(id, openedTask.task.id)
            } catch (e: NoSuchElementException) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(attached.release(openedTask))
    }

    /**
     * `POST /api/v1/reports/{id}/tasks/{taskId}` — applies one task event to a task whose subject
     * is this report job: an approver decides the release's `approval` task. Same contract as the
     * prospect route — the machine holds every rule (nobody decides an approval they requested,
     * terminal tasks accept nothing) and the edge adds the governance bar: gate decisions need
     * `approver`, and a task on any other subject is 404 so the route never reveals it exists (#489).
     */
    @PostMapping("/api/v1/reports/{id}/tasks/{taskId}")
    fun taskEvent(
        @PathVariable id: UUID,
        @PathVariable taskId: UUID,
        @RequestBody body: TaskEventRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<TaskView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val job = jobs.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, job) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val bound =
            tasks
                .state(taskId)
                ?.takeIf { it.task.subjectType == "report-job" && it.task.subjectId == id.toString() }
                ?: return ResponseEntity.notFound().build()
        val event = body.toEvent(jwt.subject!!) ?: return ResponseEntity.badRequest().build()
        if (bound.task.kind == TaskKind.APPROVAL && event.isGateDecision() && role != TenantRole.APPROVER) {
            return ResponseEntity.notFound().build()
        }
        val after =
            try {
                tasks.append(taskId, event, TaskProvenance("api", body.correlationId ?: UUID.randomUUID()))
            } catch (_: NoSuchElementException) {
                return ResponseEntity.notFound().build()
            } catch (_: IllegalArgumentException) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        return ResponseEntity.ok(after.taskView())
    }

    @GetMapping("/api/v1/reports/{id}/release")
    fun release(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ReleaseView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val job = jobs.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, job) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job.release(job.approvalTaskId?.let(tasks::state), showDraft = role == TenantRole.APPROVER))
    }

    private fun roleIn(
        userId: UUID,
        job: ReportJob,
    ): TenantRole? = tenants.tenantsOf(userId).firstOrNull { it.tenantId == job.request.tenantId }?.role

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()

    /** [result] is present only once the approval task is approved: before that the artifact stays inside. */
    data class ReleaseView(
        val jobId: UUID,
        val jobStatus: String,
        val approvalTaskId: UUID?,
        val taskStatus: String?,
        val released: Boolean,
        val result: JsonNode?,
        val artifactSha256: String?,
    )

    private fun ReportJob.release(
        task: TaskState?,
        showDraft: Boolean = false,
    ): ReleaseView {
        val released = releases(task)
        return ReleaseView(
            jobId = id,
            jobStatus = status.wireValue,
            approvalTaskId = approvalTaskId,
            taskStatus = task?.status?.name?.lowercase(),
            released = released,
            // An approver reads the sealed draft to decide the gate; everyone else waits (#552).
            result = if (released || showDraft) result?.let { json.readTree(it) } else null,
            artifactSha256 = if (released || showDraft) artifactSha256 else null,
        )
    }
}
