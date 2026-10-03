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
 * left due under the lease, so it is retried once per lease and lands when the queue drains.
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
        val scope = TenantScope.Tenants(listOf(schedule.tenantId))
        if (jobs.pendingCount(schedule.tenantId, scope) >= PENDING_REPORT_LIMIT) {
            log.warn("report schedule {} deferred: tenant {} queue is full", schedule.id, schedule.tenantId)
            return
        }
        // A template that cannot build a request is broken the same way every pass — park it
        // like a bad cron instead of retrying once per lease forever (#487). Transient submit
        // failures still bubble to the poll, where the lease covers the retry.
        val request =
            try {
                schedule.toRequest(UUID.randomUUID())
            } catch (e: IllegalArgumentException) {
                disable(schedule, e.message ?: "malformed request template")
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
                ?: disable(schedule, "has no next occurrence")
        } catch (e: IllegalArgumentException) {
            disable(schedule, e.message ?: "unparseable cron")
        }

    private fun disable(
        schedule: ReportSchedule,
        reason: String,
    ): Instant? {
        log.error("report schedule {} parked: {} — {}", schedule.id, schedule.cron, reason)
        schedules.markRun(schedule.id, Instant.now().plus(100L * 365, ChronoUnit.DAYS))
        return null
    }
}
