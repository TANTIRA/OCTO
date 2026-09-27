package com.octo.api.report

import com.octo.workflow.TenantScope
import com.octo.workflow.report.ReportSchedule
import com.octo.workflow.report.ReportSchedules
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** In-memory `ReportSchedules` honoring the lease semantics, for the endpoint and runner tests. */
class FakeReportSchedules : ReportSchedules {
    val schedules = linkedMapOf<UUID, ReportSchedule>()

    override fun upsert(
        schedule: ReportSchedule,
        scope: TenantScope,
    ): ReportSchedule {
        schedules[schedule.id] = schedule
        return schedule
    }

    override fun load(
        id: UUID,
        scope: TenantScope,
    ) = schedules[id]

    override fun list(
        tenantId: UUID,
        scope: TenantScope,
    ) = schedules.values.filter { it.tenantId == tenantId }

    override fun claimDue(
        now: Instant,
        lease: Duration,
    ): List<ReportSchedule> =
        schedules.values
            .filter { it.active && it.nextRunAt <= now && it.claimedUntil.let { until -> until == null || until <= now } }
            .onEach { schedules[it.id] = it.copy(claimedUntil = now.plus(lease)) }

    override fun markRun(
        id: UUID,
        nextRunAt: Instant,
    ) {
        val schedule = schedules[id] ?: throw NoSuchElementException("no report schedule $id")
        schedules[id] = schedule.copy(nextRunAt = nextRunAt, claimedUntil = null)
    }
}
