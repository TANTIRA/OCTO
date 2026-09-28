package com.octo.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.persistence.TenantScope
import com.octo.workflow.report.ReportSchedule
import com.octo.workflow.report.ReportSchedules
import com.octo.workflow.report.ReportType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.support.CronExpression
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * `POST /api/v1/report-schedules` registers a cadence; `PUT` edits it (tenant and id immutable);
 * `GET` reads one or lists a tenant's. Writes need a working role — a schedule submits real report
 * jobs — reads need any role; another tenant's schedule is 404 like every other cross-tenant read.
 */
@RestController
class ReportScheduleController(
    private val schedules: ReportSchedules,
    private val tenants: TenantDirectory,
    private val json: ObjectMapper,
) {
    @PostMapping("/api/v1/report-schedules")
    fun create(
        @Valid @RequestBody body: ScheduleBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ScheduleView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val access = tenants.tenantsOf(userId).firstOrNull { it.tenantId == body.tenantId }
        if (access == null || access.role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val schedule = body.toSchedule(UUID.randomUUID()) ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(schedules.upsert(schedule, TenantScope.User(userId)).view())
    }

    @PutMapping("/api/v1/report-schedules/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: ScheduleBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ScheduleView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val stored = schedules.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val access = tenants.tenantsOf(userId).firstOrNull { it.tenantId == stored.tenantId }
        if (access == null || access.role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (stored.tenantId != body.tenantId) return ResponseEntity.badRequest().build()
        val schedule = body.toSchedule(stored.id) ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(schedules.upsert(schedule, TenantScope.User(userId)).view())
    }

    @GetMapping("/api/v1/report-schedules/{id}")
    fun load(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ScheduleView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val schedule = schedules.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        if (tenants.tenantsOf(userId).none { it.tenantId == schedule.tenantId }) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(schedule.view())
    }

    @GetMapping("/api/v1/report-schedules")
    fun list(
        @RequestParam tenantId: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<ScheduleView>> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        if (tenants.tenantsOf(userId).none { it.tenantId == tenantId }) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(schedules.list(tenantId, TenantScope.User(userId)).map { it.view() })
    }

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject) }.getOrNull()

    /** Cron is validated here so a bad expression can never reach the table. */
    private fun ScheduleBody.toSchedule(id: UUID): ReportSchedule? {
        // GL export is on-demand only: a schedule holds a fixed template, but a journal needs the current
        // event set each run, and V28 widened the type check on report_job, not report_schedule.
        val type = ReportType.entries.firstOrNull { it.wireValue == this.type && it != ReportType.GL_EXPORT } ?: return null
        val next =
            try {
                CronExpression.parse(cron).next(ZonedDateTime.now(ZoneOffset.UTC))?.let { Instant.from(it) }
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
        val now = Instant.now()
        return ReportSchedule(
            id = id,
            tenantId = tenantId,
            name = name,
            reportType = type,
            positionSourceType = positionSourceType,
            positionSourceId = positionSourceId,
            measures = measures,
            parameters = json.writeValueAsString(parameters ?: emptyMap<String, Any?>()),
            cron = cron,
            nextRunAt = next,
            active = active,
            claimedUntil = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    data class ScheduleBody(
        val tenantId: UUID,
        @field:NotBlank val name: String,
        @field:NotBlank val type: String,
        @field:NotBlank val positionSourceType: String,
        @field:NotBlank val positionSourceId: String,
        val measures: List<String> = emptyList(),
        val parameters: Map<String, Any?>? = null,
        @field:NotBlank val cron: String,
        val active: Boolean = true,
    )

    data class ScheduleView(
        val id: UUID,
        val tenantId: UUID,
        val name: String,
        val type: String,
        val positionSourceType: String,
        val positionSourceId: String,
        val measures: List<String>,
        val cron: String,
        val nextRunAt: Instant,
        val active: Boolean,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private fun ReportSchedule.view() =
        ScheduleView(
            id,
            tenantId,
            name,
            reportType.wireValue,
            positionSourceType,
            positionSourceId,
            measures,
            cron,
            nextRunAt,
            active,
            createdAt,
            updatedAt,
        )
}
