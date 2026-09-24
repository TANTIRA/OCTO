package com.mesta.asset.workflow

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val T0 = Instant.parse("2026-09-24T09:00:00Z")

private fun at(minutes: Long) = T0.plusSeconds(minutes * 60)

private fun task(kind: TaskKind = TaskKind.APPROVAL) = Task(UUID.randomUUID(), kind, "valuation-event", "ve-1", "alice", T0)

class TaskTest {
    @Test
    fun `an approval is decided by someone other than its requester`() {
        val state =
            replay(
                task(),
                listOf(TaskEvent.Assigned("alice", at(1), assignee = "bob"), TaskEvent.Approved("bob", at(2))),
            )

        assertEquals(TaskStatus.APPROVED, state.status)
        assertEquals("bob", state.decidedBy)
        assertEquals("bob", state.assignee)
        assertEquals(at(2), state.lastEventAt)
    }

    @Test
    fun `nobody approves, rejects or is assigned their own request`() {
        val open = opened(task())

        assertFailsWith<IllegalArgumentException> { open.next(TaskEvent.Approved("alice", at(1))) }
        assertFailsWith<IllegalArgumentException> { open.next(TaskEvent.Rejected("alice", at(1), "no")) }
        assertFailsWith<IllegalArgumentException> { open.next(TaskEvent.Assigned("carol", at(1), assignee = "alice")) }
    }

    @Test
    fun `rework returns the task to its requester, who resubmits`() {
        val reworked = opened(task()).next(TaskEvent.ReworkRequested("bob", at(1), "valuation method not stated"))
        assertEquals(TaskStatus.IN_REWORK, reworked.status)
        assertNull(reworked.decidedBy)

        assertFailsWith<IllegalArgumentException> { reworked.next(TaskEvent.Approved("bob", at(2))) }
        assertFailsWith<IllegalArgumentException> { reworked.next(TaskEvent.Resubmitted("bob", at(2))) }

        val decided = reworked.next(TaskEvent.Resubmitted("alice", at(2))).next(TaskEvent.Rejected("carol", at(3), "still unsupported"))
        assertEquals(TaskStatus.REJECTED, decided.status)
        assertEquals("carol", decided.decidedBy)
    }

    @Test
    fun `rejection, rework and cancellation need a rationale`() {
        assertFailsWith<IllegalArgumentException> { TaskEvent.Rejected("bob", at(1), " ") }
        assertFailsWith<IllegalArgumentException> { TaskEvent.ReworkRequested("bob", at(1), "") }
        assertFailsWith<IllegalArgumentException> { TaskEvent.Cancelled("alice", at(1), " ") }
    }

    @Test
    fun `nothing follows a terminal status`() {
        val approved = opened(task()).next(TaskEvent.Approved("bob", at(1)))

        assertFailsWith<IllegalArgumentException> { approved.next(TaskEvent.Cancelled("alice", at(2), "changed my mind")) }
        assertFailsWith<IllegalArgumentException> { approved.next(TaskEvent.Rejected("carol", at(2), "second opinion")) }
    }

    @Test
    fun `review and evidence tasks are completed, not approved`() {
        val review = opened(task(TaskKind.REVIEW))

        assertFailsWith<IllegalArgumentException> { review.next(TaskEvent.Approved("bob", at(1))) }
        assertEquals(TaskStatus.COMPLETED, review.next(TaskEvent.Completed("alice", at(1))).status)
        assertFailsWith<IllegalArgumentException> { opened(task()).next(TaskEvent.Completed("bob", at(1))) }
        assertEquals("evidence-request", TaskKind.EVIDENCE_REQUEST.wireValue)
    }

    @Test
    fun `an open or reworked task can be cancelled with a rationale`() {
        val cancelled =
            opened(task())
                .next(TaskEvent.ReworkRequested("bob", at(1), "missing source"))
                .next(TaskEvent.Cancelled("alice", at(2), "deal withdrawn"))

        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals("alice", cancelled.decidedBy)
    }

    @Test
    fun `events out of time order or without an actor are rejected`() {
        val assigned = opened(task()).next(TaskEvent.Assigned("alice", at(5), assignee = "bob"))

        assertFailsWith<IllegalArgumentException> { assigned.next(TaskEvent.Approved("bob", at(4))) }
        assertFailsWith<IllegalArgumentException> { assigned.next(TaskEvent.Approved(" ", at(6))) }
        assertFailsWith<IllegalArgumentException> { Task(UUID.randomUUID(), TaskKind.REVIEW, "", "x", "alice", T0) }
    }
}
