package com.octo.api.report

import com.octo.workflow.TenantScope
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
 * so a crash between the two re-fires once the lease lapses (at-least-once, never lost).
 */
class ReportScheduleRunner(
    private val schedules: ReportSchedules,
    private val jobs: ReportJobs,
    private val lease: Duration = Duration.ofMinutes(1),
) {
    private val log = LoggerFactory.getLogger(ReportScheduleRunner::class.java)

    @Scheduled(fixedDelayString = "\${mesta.reports.schedules.poll-ms:30000}")
    fun poll() = schedules.claimDue(Instant.now(), lease).forEach(::fire)

    fun fire(schedule: ReportSchedule) {
        val next = nextRun(schedule) ?: return
        jobs.submit(schedule.toRequest(UUID.randomUUID()), TenantScope.Tenants(listOf(schedule.tenantId)))
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
        log.error("report schedule {} parked: cron '{}' {}", schedule.id, schedule.cron, reason)
        schedules.markRun(schedule.id, Instant.now().plus(100L * 365, ChronoUnit.DAYS))
        return null
    }
}
