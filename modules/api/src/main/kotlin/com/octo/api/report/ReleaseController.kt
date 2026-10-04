package com.octo.api.report

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.decideTask
import com.octo.api.mayPost
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

/** The workflow tasks the release gate opens and reads; `JdbcTaskStore` behind it in production. */
interface ReleaseTasks {
    /** The subject's already-open task of this kind, or [task] freshly opened — two racers never mint two. */
    fun openUnlessOpen(
        task: Task,
        provenance: TaskProvenance,
    ): TaskState

    fun state(taskId: UUID): TaskState?

    /** Appends one event; the store's state machine is the only writer of transitions. */
    fun append(
        taskId: UUID,
        event: TaskEvent,
        provenance: TaskProvenance,
    ): TaskState
}

/**
 * Whether [task] — a report job's approval task — releases the job's artifact. Release is this check
 * alone (#482). Reading the draft does not approve the task and does not satisfy it.
 */
internal fun releases(task: TaskState?): Boolean = task?.status == TaskStatus.APPROVED

/**
 * Who may read the result and its hash. A released artifact is visible to every member of the tenant.
 * Before release, only an approver may read the sealed draft (#552), so they can see what a decision
 * would publish. Viewers stay on the published artifact. Analysts and admins stay behind the gate
 * #482 closed — an admin is segregated from approval duties and is not the person deciding the release.
 * A true result here does not make [releases] true.
 */
internal fun revealsDraft(
    released: Boolean,
    role: TenantRole?,
): Boolean = released || role == TenantRole.APPROVER

/** The `subject_type` a release task binds to — opened and decided against this one literal. */
private const val RELEASE_SUBJECT = "report-job"

/**
 * The approval gate of #6 slice 7: an outbound artifact passes a `workflow_task` of kind `approval` before
 * release. `POST /api/v1/reports/{id}/release` opens that task for a `done` job, once; `GET …/release` tells
 * whether the task is approved. The result travels with that view once released, and also to an approver
 * while the task is still open (#552). Segregation of duties (V5, V7): the requester of the task cannot
 * be the one who approves it.
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
        val task = Task(UUID.randomUUID(), TaskKind.APPROVAL, RELEASE_SUBJECT, id.toString(), jwt.subject!!, Instant.now())
        // openUnlessOpen dedupes on the job subject, so a loser adopts the winner's task instead of
        // orphaning one nobody can decide; its attach then finds the slot taken and is a 409.
        val openedTask = tasks.openUnlessOpen(task, TaskProvenance("api", job.request.correlationId))
        val attached =
            try {
                jobs.attachApproval(id, openedTask.task.id)
            } catch (e: NoSuchElementException) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(attached.release(openedTask, role))
    }

    @GetMapping("/api/v1/reports/{id}/release")
    fun release(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ReleaseView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val job = jobs.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, job) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job.release(job.approvalTaskId?.let(tasks::state), role))
    }

    /**
     * `POST /api/v1/reports/{id}/release/decision` — the missing half of the gate (#489): without
     * it a release task could never be decided and the artifact stayed sealed forever. The request
     * binds to the job's own attached approval task — a job with no release requested, or a task
     * pointing at any other subject, answers 404 like every other miss here. Gate decisions are the
     * APPROVER's alone (`mayPost`), and the state machine still bars the requester from deciding
     * their own ask.
     */
    @PostMapping("/api/v1/reports/{id}/release/decision")
    fun decide(
        @PathVariable id: UUID,
        @RequestBody body: DecisionRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ReleaseView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val job = jobs.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, job) ?: return ResponseEntity.notFound().build()
        val taskId = job.approvalTaskId ?: return ResponseEntity.notFound().build()
        val task =
            tasks.state(taskId)?.takeIf {
                it.task.subjectType == RELEASE_SUBJECT && it.task.subjectId == id.toString()
            } ?: return ResponseEntity.notFound().build()
        val event = body.toEvent(jwt.subject!!) ?: return ResponseEntity.badRequest().build()
        if (!role.mayPost(task.task.kind, event)) return ResponseEntity.notFound().build()
        return decideTask(taskId, event, body.correlationId, tasks::append) { job.release(it, role) }
    }

    private fun roleIn(
        userId: UUID,
        job: ReportJob,
    ): TenantRole? = tenants.tenantsOf(userId).firstOrNull { it.tenantId == job.request.tenantId }?.role

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()

    /**
     * The two decisions a release gate accepts — `approve` releases the artifact, `reject` sends it
     * back with a mandatory rationale. Anything else fails the boundary with 400 rather than
     * persisting an event the gate does not understand.
     */
    data class DecisionRequest(
        val decision: String,
        val rationale: String? = null,
        val correlationId: UUID? = null,
    ) {
        fun toEvent(actor: String): TaskEvent? {
            val at = Instant.now()
            return when (decision) {
                "approve" -> TaskEvent.Approved(actor, at, rationale)
                "reject" -> rationale?.takeIf { it.isNotBlank() }?.let { TaskEvent.Rejected(actor, at, it) }
                else -> null
            }
        }
    }

    /**
     * [result] and [artifactSha256] are present once the approval task is approved, and also to an
     * approver while it is still open (#552). [released] stays false until the task is approved.
     */
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
        role: TenantRole,
    ): ReleaseView {
        val released = releases(task)
        val reveal = revealsDraft(released, role)
        return ReleaseView(
            jobId = id,
            jobStatus = status.wireValue,
            approvalTaskId = approvalTaskId,
            taskStatus = task?.status?.name?.lowercase(),
            released = released,
            result = if (reveal) result?.let { json.readTree(it) } else null,
            artifactSha256 = if (reveal) artifactSha256 else null,
        )
    }
}
