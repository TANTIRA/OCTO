package com.octo.api.report

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.isBoundedObject
import com.octo.persistence.TenantScope
import com.octo.workflow.TaskState
import com.octo.workflow.report.PENDING_REPORT_LIMIT
import com.octo.workflow.report.ReportJob
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportRequest
import com.octo.workflow.report.ReportType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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

/**
 * Marquee report service (#6 slice 7): `POST /api/v1/reports` queues a job, `GET /api/v1/reports/{id}` reads its
 * status and result. Submitting needs a working role in the tenant (analyst, approver or admin, per V8);
 * reading needs any role. Another tenant's job and an unknown id are 404 (default deny). The result and its
 * artifact hash stay withheld until the release gate's approval task is approved, exactly as `GET …/release`.
 */
@RestController
class ReportController(
    private val jobs: ReportJobs,
    private val tasks: ReleaseTasks,
    private val tenants: TenantDirectory,
    private val json: ObjectMapper,
) {
    @PostMapping("/api/v1/reports")
    fun submit(
        @Valid @RequestBody body: SubmitRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<JobView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val access = tenants.tenantsOf(userId).firstOrNull { it.tenantId == body.tenantId }
        if (access == null || access.role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val type = ReportType.entries.firstOrNull { it.wireValue == body.type } ?: return ResponseEntity.badRequest().build()
        if (!reportInputsBounded(json, body.measures, body.parameters)) return ResponseEntity.badRequest().build()
        val scope = TenantScope.User(userId)
        if (jobs.pendingCount(body.tenantId, scope) >= PENDING_REPORT_LIMIT) {
            // A full queue drains as the runner works through it; the caller retries later.
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build()
        }
        val job =
            jobs.submit(
                ReportRequest(
                    tenantId = body.tenantId,
                    type = type,
                    positionSourceType = body.positionSourceType,
                    positionSourceId = body.positionSourceId,
                    measures = body.measures,
                    parameters = json.writeValueAsString(body.parameters ?: emptyMap<String, Any?>()),
                    requestedBy = jwt.subject!!,
                    correlationId = UUID.randomUUID(),
                ),
                scope,
            )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.view())
    }

    @GetMapping("/api/v1/reports/{id}")
    fun status(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<JobView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val job = jobs.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val access = tenants.tenantsOf(userId).firstOrNull { it.tenantId == job.request.tenantId } ?: return ResponseEntity.notFound().build()
        val task = job.approvalTaskId?.let(tasks::state)
        // An approver reads the sealed draft so they can decide the gate; everyone else waits
        // for the release (#552). The `released` flag still reports the true gate state.
        return ResponseEntity.ok(job.view(task, showDraft = access.role == TenantRole.APPROVER))
    }

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()

    data class SubmitRequest(
        val tenantId: UUID,
        @field:NotBlank val type: String,
        @field:NotBlank @field:Size(max = MAX_REPORT_FIELD_LENGTH) val positionSourceType: String,
        @field:NotBlank @field:Size(max = MAX_REPORT_FIELD_LENGTH) val positionSourceId: String,
        @field:Size(max = MAX_REPORT_MEASURES) val measures: List<String> = emptyList(),
        val parameters: Map<String, Any?>? = null,
    )

    /**
     * [result] is the job's result object; it is opaque here and typed by the report's engine adapter. It and
     * [artifactSha256] are null until the job is released — or the reader is an approver reviewing the
     * draft (#482, #552). [approvalTaskId], [taskStatus], [taskRequestedBy] and [released] carry the
     * release gate's state so a queue can tell "release pending" from "not yet requested" and show the
     * artifact once approved (#490).
     */
    data class JobView(
        val id: UUID,
        val tenantId: UUID,
        val type: String,
        val positionSourceType: String,
        val positionSourceId: String,
        val measures: List<String>,
        val status: String,
        val result: JsonNode?,
        val error: String?,
        val artifactSha256: String?,
        val approvalTaskId: UUID?,
        val taskStatus: String?,
        val taskRequestedBy: String?,
        val released: Boolean,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private fun ReportJob.view(
        task: TaskState? = null,
        showDraft: Boolean = false,
    ) = JobView(
        id = id,
        tenantId = request.tenantId,
        type = request.type.wireValue,
        positionSourceType = request.positionSourceType,
        positionSourceId = request.positionSourceId,
        measures = request.measures,
        status = status.wireValue,
        result = if (releases(task) || showDraft) result?.let { json.readTree(it) } else null,
        error = error,
        artifactSha256 = if (releases(task) || showDraft) artifactSha256 else null,
        approvalTaskId = approvalTaskId,
        taskStatus = task?.status?.name?.lowercase(),
        taskRequestedBy = task?.task?.requestedBy,
        released = releases(task),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private companion object {
        val JSON =
            com.fasterxml.jackson.databind
                .ObjectMapper()
    }
}

/** Bound on a report's position-source fields and on each measure name; matches the agent-run subject id bound. */
internal const val MAX_REPORT_FIELD_LENGTH = 200

/** Bound on how many measures one report (or schedule template) may request. */
internal const val MAX_REPORT_MEASURES = 50

/** Measure names and the free-form `parameters` object are capped the same way for one-off reports and schedules. */
internal fun reportInputsBounded(
    json: ObjectMapper,
    measures: List<String>,
    parameters: Map<String, Any?>?,
): Boolean = measures.none { it.isBlank() || it.length > MAX_REPORT_FIELD_LENGTH } && json.isBoundedObject(parameters)
