package com.octo.workflow.report

import com.octo.workflow.TenantScope
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A tenant's recurring report obligation (#211): the `ReportRequest` template plus the `cron` that
 * drives it. Mutable operational config — the audit trail lives on the jobs it submits.
 */
data class ReportSchedule(
    val id: UUID,
    val tenantId: UUID,
    val name: String,
    val reportType: ReportType,
    val positionSourceType: String,
    val positionSourceId: String,
    val measures: List<String>,
    val parameters: String,
    val cron: String,
    val nextRunAt: Instant,
    val active: Boolean,
    val claimedUntil: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank() && cron.isNotBlank()) { "a schedule names itself and its cadence" }
        require(positionSourceType.isNotBlank() && positionSourceId.isNotBlank()) { "a schedule names its position source" }
    }

    /** The request this schedule submits when it fires. `requestedBy` records that a calendar — not a person — asked. */
    fun toRequest(correlationId: UUID): ReportRequest =
        ReportRequest(
            tenantId = tenantId,
            type = reportType,
            positionSourceType = positionSourceType,
            positionSourceId = positionSourceId,
            measures = measures,
            parameters = parameters,
            requestedBy = "schedule:$id",
            correlationId = correlationId,
        )
}

/** The schedule store the endpoints and the scheduler poll depend on. */
interface ReportSchedules {
    /** Creates or replaces a schedule by id. `tenant_id` is immutable — moving a schedule between tenants means a new row. */
    fun upsert(
        schedule: ReportSchedule,
        scope: TenantScope,
    ): ReportSchedule

    fun load(
        id: UUID,
        scope: TenantScope,
    ): ReportSchedule?

    fun list(
        tenantId: UUID,
        scope: TenantScope,
    ): List<ReportSchedule>

    /**
     * Takes a [lease] on every due schedule (`active`, `next_run_at <= now`, no live claim) and
     * returns the claimed rows. Two concurrent pollers never claim the same schedule.
     */
    fun claimDue(
        now: Instant,
        lease: Duration,
    ): List<ReportSchedule>

    /** Clears the claim and advances [nextRunAt] to the cadence's next occurrence. */
    fun markRun(
        id: UUID,
        nextRunAt: Instant,
    )
}
