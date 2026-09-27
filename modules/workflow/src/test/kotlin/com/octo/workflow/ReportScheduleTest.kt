package com.octo.workflow

import com.octo.workflow.report.ReportSchedule
import com.octo.workflow.report.ReportType
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `ReportSchedule` invariants and the request it renders when the cadence fires. */
class ReportScheduleTest {
    private fun schedule(
        name: String = "LP weekly",
        cron: String = "0 0 8 * * MON",
        positionSourceId: String = "fund-1",
    ) = ReportSchedule(
        id = UUID.randomUUID(),
        tenantId = UUID.randomUUID(),
        name = name,
        reportType = ReportType.PERFORMANCE,
        positionSourceType = "fund",
        positionSourceId = positionSourceId,
        measures = listOf("tvpi"),
        parameters = "{}",
        cron = cron,
        nextRunAt = Instant.now(),
        active = true,
        claimedUntil = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `a schedule requires a name a cadence and a position source`() {
        assertFailsWith<IllegalArgumentException> { schedule(name = " ") }
        assertFailsWith<IllegalArgumentException> { schedule(cron = "") }
        assertFailsWith<IllegalArgumentException> { schedule(positionSourceId = " ") }
    }

    @Test
    fun `toRequest carries the template and names the schedule as the requester`() {
        val schedule = schedule()
        val correlation = UUID.randomUUID()
        val request = schedule.toRequest(correlation)
        assertEquals(schedule.tenantId, request.tenantId)
        assertEquals(ReportType.PERFORMANCE, request.type)
        assertEquals("schedule:${schedule.id}", request.requestedBy)
        assertEquals(listOf("tvpi"), request.measures)
        assertEquals(correlation, request.correlationId)
    }
}
