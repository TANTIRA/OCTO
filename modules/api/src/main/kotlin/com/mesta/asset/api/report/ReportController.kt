package com.mesta.asset.api.report

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.workflow.report.ReportJob
import com.mesta.asset.workflow.report.ReportJobs
import com.mesta.asset.workflow.report.ReportRequest
import com.mesta.asset.workflow.report.ReportType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
 * reading needs any role. Another tenant's job and an unknown id are 404 (default deny).
 */
@RestController
class ReportController(
    private val jobs: ReportJobs,
    private val tenants: TenantDirectory,
    private val json: ObjectMapper,
) {
    @PostMapping("/api/v1/reports")
    fun submit(
        @Valid @RequestBody body: SubmitRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<JobView> {
        val access = tenants.tenantsOf(userId(jwt) ?: return ResponseEntity.notFound().build()).firstOrNull { it.tenantId == body.tenantId }
        if (access == null || access.role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val type = ReportType.entries.firstOrNull { it.wireValue == body.type } ?: return ResponseEntity.badRequest().build()
        val job =
            jobs.submit(
                ReportRequest(
                    tenantId = body.tenantId,
                    type = type,
                    positionSourceType = body.positionSourceType,
                    positionSourceId = body.positionSourceId,
                    measures = body.measures,
                    parameters = json.writeValueAsString(body.parameters ?: emptyMap<String, Any?>()),
                    requestedBy = jwt.subject,
                    correlationId = UUID.randomUUID(),
                ),
            )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.view())
    }

    @GetMapping("/api/v1/reports/{id}")
    fun status(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<JobView> {
        val job = jobs.load(id) ?: return ResponseEntity.notFound().build()
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        if (tenants.tenantsOf(userId).none { it.tenantId == job.request.tenantId }) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job.view())
    }

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject) }.getOrNull()

    data class SubmitRequest(
        val tenantId: UUID,
        @field:NotBlank val type: String,
        @field:NotBlank val positionSourceType: String,
        @field:NotBlank val positionSourceId: String,
        val measures: List<String> = emptyList(),
        val parameters: Map<String, Any?>? = null,
    )

    /** [result] is the job's result object; it is opaque here and typed by the report's engine adapter. */
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
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private fun ReportJob.view() =
        JobView(
            id = id,
            tenantId = request.tenantId,
            type = request.type.wireValue,
            positionSourceType = request.positionSourceType,
            positionSourceId = request.positionSourceId,
            measures = request.measures,
            status = status.wireValue,
            result = result?.let { json.readTree(it) },
            error = error,
            artifactSha256 = artifactSha256,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private companion object {
        val JSON =
            com.fasterxml.jackson.databind
                .ObjectMapper()
    }
}
