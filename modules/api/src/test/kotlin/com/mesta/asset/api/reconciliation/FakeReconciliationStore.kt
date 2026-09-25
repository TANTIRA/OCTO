package com.mesta.asset.api.reconciliation

import com.mesta.asset.recon.matching.Break
import com.mesta.asset.recon.matching.IborRecord
import com.mesta.asset.recon.matching.persistence.ReconciliationStore
import java.sql.SQLException
import java.time.ZoneId
import java.util.UUID

/** In-memory `ReconciliationStore` with V15's one-task-per-key rule, for the runner and endpoint tests. */
class FakeReconciliationStore(
    private val ledger: List<IborRecord> = emptyList(),
) : ReconciliationStore {
    data class Row(
        val runId: UUID,
        val brk: Break,
        val taskId: UUID?,
    )

    val rows = mutableListOf<Row>()

    override fun iborRecords(
        sourceSystem: String,
        zone: ZoneId,
    ) = ledger.filter { it.sourceSystem == sourceSystem }

    override fun existingTask(
        tenantId: UUID,
        brk: Break,
    ) = rows.firstOrNull { it.taskId != null && it.brk.sameKey(brk) }?.taskId

    override fun record(
        tenantId: UUID,
        runId: UUID,
        brk: Break,
        taskId: UUID?,
        correlationId: UUID,
    ): UUID {
        if (taskId != null && existingTask(tenantId, brk) != null) throw SQLException("reconciliation_break_one_task", "23505")
        rows += Row(runId, brk, taskId)
        return UUID.randomUUID()
    }

    private fun Break.sameKey(other: Break) =
        kind == other.kind && sourceSystem == other.sourceSystem && sourceRef == other.sourceRef && ledgerEventId == other.ledgerEventId
}
