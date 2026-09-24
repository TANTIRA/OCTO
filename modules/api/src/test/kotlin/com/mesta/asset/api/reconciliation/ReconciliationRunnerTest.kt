package com.mesta.asset.api.reconciliation

import com.mesta.asset.recon.matching.BreakKind
import com.mesta.asset.recon.matching.IborRecord
import com.mesta.asset.recon.matching.SourceRecord
import com.mesta.asset.recon.matching.Tolerance
import com.mesta.asset.workflow.Task
import com.mesta.asset.workflow.TaskKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

class ReconciliationRunnerTest {
    private val usd = Currency.getInstance("USD")
    private val day = LocalDate.parse("2026-06-30")
    private val kept = IborRecord(UUID.randomUUID(), "admin-a", "t-1", BigDecimal("-100"), usd, day)
    private val orphan = IborRecord(UUID.randomUUID(), "admin-a", "t-9", BigDecimal("5"), usd, day)
    private val store =
        FakeReconciliationStore(listOf(kept, orphan, IborRecord(UUID.randomUUID(), "admin-b", "b-1", BigDecimal.ONE, usd, day)))
    private val opened = mutableListOf<Task>()
    private val runner = ReconciliationRunner(store, BreakTaskOpener { task, _ -> opened += task })
    private val tenantId = UUID.randomUUID()

    private fun source(
        id: String,
        amount: String,
    ) = SourceRecord("admin-a", id, BigDecimal(amount), usd, day)

    @Test
    fun `a run records every break, opens one evidence-request task each, and a re-run reuses the tasks`() {
        val first =
            runner.run(
                tenantId,
                listOf(source("t-1", "-100.5"), source("t-2", "40")),
                Tolerance.EXACT,
                ZoneOffset.UTC,
                "recon-runner",
                UUID.randomUUID(),
            )

        assertThat(first.matched).isEqualTo(1)
        assertThat(
            first.outcomes.map {
                it.brk.kind
            },
        ).containsExactly(BreakKind.AMOUNT_MISMATCH, BreakKind.MISSING_IN_IBOR, BreakKind.MISSING_IN_SOURCE)
        assertThat(first.outcomes).allMatch { it.opened }
        assertThat(opened).hasSize(3)
        assertThat(opened.first().kind).isEqualTo(TaskKind.EVIDENCE_REQUEST)
        assertThat(opened.first().subjectType).isEqualTo("reconciliation-break")
        assertThat(opened.first().subjectId).isEqualTo("amount-mismatch/admin-a/t-1/${kept.id}")
        assertThat(opened.first().requestedBy).isEqualTo("recon-runner")
        assertThat(store.rows).hasSize(3)
        assertThat(store.rows.map { it.runId }.toSet()).containsExactly(first.runId)

        val again =
            runner.run(
                tenantId,
                listOf(source("t-1", "-100.5"), source("t-2", "40")),
                Tolerance.EXACT,
                ZoneOffset.UTC,
                "recon-runner",
                UUID.randomUUID(),
            )
        assertThat(again.outcomes.map { it.taskId }).isEqualTo(first.outcomes.map { it.taskId })
        assertThat(again.outcomes).noneMatch { it.opened }
        assertThat(opened).hasSize(3)
        assertThat(store.rows).hasSize(6)
        assertThat(store.rows.drop(3)).allMatch { it.taskId == null }
    }

    @Test
    fun `within tolerance and with every event claimed there is nothing to open, and a race adopts the winner`() {
        val clean =
            runner.run(
                tenantId,
                listOf(source("t-1", "-100.4"), source("t-9", "5")),
                Tolerance(BigDecimal("0.5"), 0),
                ZoneOffset.UTC,
                "r",
                UUID.randomUUID(),
            )
        assertThat(clean.matched).isEqualTo(2)
        assertThat(clean.outcomes).isEmpty()
        assertThat(opened).isEmpty()

        val winner = UUID.randomUUID()
        val racing =
            ReconciliationRunner(
                object : com.mesta.asset.recon.matching.persistence.ReconciliationStore by store {
                    override fun existingTask(
                        tenantId: UUID,
                        brk: com.mesta.asset.recon.matching.Break,
                    ) = store.existingTask(tenantId, brk).also {
                        if (it ==
                            null
                        ) {
                            store.record(tenantId, UUID.randomUUID(), brk, winner, UUID.randomUUID())
                        }
                    }
                },
                BreakTaskOpener { task, _ -> opened += task },
            )
        val raced =
            racing.run(
                tenantId,
                listOf(source("t-2", "40"), source("t-1", "-100"), source("t-9", "5")),
                Tolerance.EXACT,
                ZoneOffset.UTC,
                "r",
                UUID.randomUUID(),
            )
        val outcome = raced.outcomes.single()
        assertThat(outcome.brk.kind).isEqualTo(BreakKind.MISSING_IN_IBOR)
        assertThat(outcome.taskId).isEqualTo(winner)
        assertThat(outcome.opened).isFalse()
    }
}
