package com.octo.api.report

import com.octo.persistence.TenantScope
import com.octo.workflow.report.ReportSchedule
import com.octo.workflow.report.ReportType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** `ReportScheduleRunner.fire`: a fired schedule submits its template with calendar provenance and advances its cadence. */
class ReportScheduleRunnerTest {
    private val jobs = FakeReportJobs()
    private val schedules = FakeReportSchedules()
    private val runner = ReportScheduleRunner(schedules, jobs)

    private fun schedule(cron: String = "0 0 8 * * MON") =
        ReportSchedule(
            id = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            name = "LP weekly",
            reportType = ReportType.PERFORMANCE,
            positionSourceType = "fund",
            positionSourceId = "fund-1",
            measures = listOf("tvpi"),
            parameters = "{}",
            cron = cron,
            nextRunAt = Instant.now().minus(1, ChronoUnit.MINUTES),
            active = true,
            claimedUntil = Instant.now().plus(1, ChronoUnit.MINUTES),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `fire submits the template with schedule provenance and advances next_run_at`() {
        val schedule = schedule()
        schedules.upsert(schedule, TenantScope.All)

        runner.fire(schedule)

        val job = jobs.jobs.values.single()
        assertThat(job.request.requestedBy).isEqualTo("schedule:${schedule.id}")
        assertThat(job.request.tenantId).isEqualTo(schedule.tenantId)
        assertThat(job.request.type).isEqualTo(ReportType.PERFORMANCE)

        val reloaded = schedules.load(schedule.id, TenantScope.All)!!
        assertThat(reloaded.claimedUntil).isNull()
        assertThat(reloaded.nextRunAt).isAfter(Instant.now())
    }

    @Test
    fun `an unparseable cron parks the schedule instead of submitting`() {
        val schedule = schedule(cron = "not a cron")
        schedules.upsert(schedule, TenantScope.All)

        runner.fire(schedule)

        assertThat(jobs.jobs).isEmpty()
        val reloaded = schedules.load(schedule.id, TenantScope.All)!!
        assertThat(reloaded.nextRunAt).isAfter(Instant.now().plus(90L * 365, ChronoUnit.DAYS))
    }
}
