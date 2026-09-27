package com.octo.dealsourcing

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pipeline state machine, replayed in memory — the rules `JdbcProspectStore.append` enforces transactionally. */
class ProspectStateTest {
    private val t0 = Instant.parse("2026-09-01T00:00:00Z")
    private val prospect =
        Prospect(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "acme-logistics",
            ProspectSource.MANUAL,
            sector = null,
            region = null,
            description = null,
            registeredAt = t0,
        )

    private fun at(seconds: Long) = t0.plusSeconds(seconds)

    @Test
    fun `the full path to invested replays in order`() {
        val events =
            listOf(
                ProspectEvent.Advanced("a", at(1), ProspectStage.SOURCED, ProspectStage.SCREENING),
                ProspectEvent.Advanced("a", at(2), ProspectStage.SCREENING, ProspectStage.DUE_DILIGENCE),
                ProspectEvent.Advanced("b", at(3), ProspectStage.DUE_DILIGENCE, ProspectStage.IC_REVIEW),
                ProspectEvent.Invested("chair", at(4), "corridor thesis"),
            )
        val state = replay(prospect, events)
        assertEquals(ProspectStage.INVESTED, state.stage)
        assertEquals("chair", state.decidedBy)
        assertTrue(state.terminal)
    }

    @Test
    fun `a pass from any open stage needs its rationale`() {
        val passed =
            replay(
                prospect,
                listOf(ProspectEvent.Passed("a", at(1), ProspectStage.SOURCED, "not a fit")),
            )
        assertEquals(ProspectStage.PASSED, passed.stage)

        assertFailsWith<IllegalArgumentException> {
            replay(prospect, listOf(ProspectEvent.Passed("a", at(1), ProspectStage.SOURCED, " ")))
        }
    }

    @Test
    fun `stages cannot be skipped, claimed backwards, or moved after terminal`() {
        assertFailsWith<IllegalArgumentException> {
            replay(prospect, listOf(ProspectEvent.Advanced("a", at(1), ProspectStage.SOURCED, ProspectStage.IC_REVIEW)))
        }
        assertFailsWith<IllegalArgumentException> {
            replay(prospect, listOf(ProspectEvent.Advanced("a", at(1), ProspectStage.SCREENING, ProspectStage.DUE_DILIGENCE)))
        }
        assertFailsWith<IllegalArgumentException> {
            replay(prospect, listOf(ProspectEvent.Invested("c", at(1), "too early")))
        }
        assertFailsWith<IllegalArgumentException> {
            replay(
                prospect,
                listOf(
                    ProspectEvent.Passed("a", at(1), ProspectStage.SOURCED, "out"),
                    ProspectEvent.Advanced("a", at(2), ProspectStage.PASSED, ProspectStage.SCREENING),
                ),
            )
        }
    }

    @Test
    fun `events must move time forward`() {
        assertFailsWith<IllegalArgumentException> {
            replay(
                prospect,
                listOf(ProspectEvent.Advanced("a", t0.minusSeconds(1), ProspectStage.SOURCED, ProspectStage.SCREENING)),
            )
        }
    }
}
