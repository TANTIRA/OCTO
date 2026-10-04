package com.octo.api.report

import com.octo.persistence.TenantScope
import com.octo.workflow.report.PENDING_REPORT_LIMIT
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportSchedule
import com.octo.workflow.report.ReportSchedules
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Fires due report schedules into the job queue (#211). `claimDue` leases the rows so this poll and
 * a second instance's never double-fire; `markRun` advances `next_run_at` only after the job lands,
 * so a crash between the two re-fires once the lease lapses (at-least-once, never lost). A tenant whose
 * queue is at [PENDING_REPORT_LIMIT] — the same cap `POST /api/v1/reports` answers 429 at — has its fire
 * left due under the lease, so it is retried once per lease and lands when the queue drains. A template that
 * cannot become a report request — a whitespace-only measure, for example — is parked the same way as an
 * unparseable cron, so one tenant's bad schedule is not claimed again every lease.
 */
class ReportScheduleRunner(
    private val schedules: ReportSchedules,
    private val jobs: ReportJobs,
    private val lease: Duration = Duration.ofMinutes(1),
) {
    private val log = LoggerFactory.getLogger(ReportScheduleRunner::class.java)

    @Scheduled(fixedDelayString = "\${octo.reports.schedules.poll-ms:30000}")
    fun poll() =
        schedules.claimDue(Instant.now(), lease).forEach { schedule ->
            // One failing schedule must not strand the rest of the batch; its lease lapses and it is retried.
            try {
                fire(schedule)
            } catch (e: Exception) {
                log.error("report schedule {} failed to fire; retrying after the lease", schedule.id, e)
            }
        }

    fun fire(schedule: ReportSchedule) {
        val next = nextRun(schedule) ?: return
        val request =
            try {
                schedule.toRequest(UUID.randomUUID())
            } catch (e: IllegalArgumentException) {
                // The row is already stored, so rejecting it at the endpoint is not enough: park it
                // instead of throwing, which would only release the lease and claim it again forever.
                park(schedule, e.message ?: "invalid report template")
                return
            }
        val scope = TenantScope.Tenants(listOf(schedule.tenantId))
        if (jobs.pendingCount(schedule.tenantId, scope) >= PENDING_REPORT_LIMIT) {
            log.warn("report schedule {} deferred: tenant {} queue is full", schedule.id, schedule.tenantId)
            return
        }
        jobs.submit(request, scope)
        schedules.markRun(schedule.id, next)
        log.info("report schedule {} fired; next run {}", schedule.id, next)
    }

    /** A cron that can't yield a next instant parks the schedule a century out — visible, never hot-looping. */
    private fun nextRun(schedule: ReportSchedule): Instant? =
        try {
            CronExpression.parse(schedule.cron).next(ZonedDateTime.now(ZoneOffset.UTC))?.let { Instant.from(it) }
                ?: park(schedule, "cron '${schedule.cron}' has no next occurrence")
        } catch (e: IllegalArgumentException) {
            park(schedule, "cron '${schedule.cron}' ${e.message ?: "unparseable"}")
        }

    /** Moves the schedule a century out so the poller stops leasing it. Returns null so callers stop the fire. */
    private fun park(
        schedule: ReportSchedule,
        reason: String,
    ): Instant? {
        log.error("report schedule {} parked: {}", schedule.id, reason)
        schedules.markRun(schedule.id, Instant.now().plus(100L * 365, ChronoUnit.DAYS))
        return null
    }
}
