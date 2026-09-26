package com.mesta.asset.api.report

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.workflow.Task
import com.mesta.asset.workflow.TaskKind
import com.mesta.asset.workflow.TaskState
import com.mesta.asset.workflow.TaskStatus
import com.mesta.asset.workflow.persistence.TaskProvenance
import com.mesta.asset.workflow.report.JobStatus
import com.mesta.asset.workflow.report.ReportJob
import com.mesta.asset.workflow.report.ReportJobs
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** The workflow tasks the release gate opens and reads; `JdbcTaskStore` behind it in production. */
interface ReleaseTasks {
    fun open(
        task: Task,
        provenance: TaskProvenance,
    )

    fun state(taskId: UUID): TaskState?
}

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
        val job = jobs.load(id) ?: return ResponseEntity.notFound().build()
        val role = roleIn(jwt, job) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (job.status != JobStatus.DONE || job.approvalTaskId != null) return ResponseEntity.status(HttpStatus.CONFLICT).build()
        val task = Task(UUID.randomUUID(), TaskKind.APPROVAL, "report-job", id.toString(), jwt.subject, Instant.now())
        tasks.open(task, TaskProvenance("api", job.request.correlationId))
        val attached = jobs.attachApproval(id, task.id)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(attached.release(tasks.state(task.id)))
    }

    @GetMapping("/api/v1/reports/{id}/release")
    fun release(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ReleaseView> {
        val job = jobs.load(id) ?: return ResponseEntity.notFound().build()
        roleIn(jwt, job) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job.release(job.approvalTaskId?.let(tasks::state)))
    }

    private fun roleIn(
        jwt: Jwt,
        job: ReportJob,
    ): TenantRole? {
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return null
        return tenants.tenantsOf(userId).firstOrNull { it.tenantId == job.request.tenantId }?.role
    }

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

    private fun ReportJob.release(task: TaskState?): ReleaseView {
        val released = task?.status == TaskStatus.APPROVED
        return ReleaseView(
            jobId = id,
            jobStatus = status.wireValue,
            approvalTaskId = approvalTaskId,
            taskStatus = task?.status?.name?.lowercase(),
            released = released,
            result = if (released) result?.let { json.readTree(it) } else null,
            artifactSha256 = if (released) artifactSha256 else null,
        )
    }
}
