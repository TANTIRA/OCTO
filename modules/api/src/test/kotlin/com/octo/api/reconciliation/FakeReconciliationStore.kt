package com.octo.api.reconciliation

import com.octo.persistence.TenantScope
import com.octo.recon.matching.Break
import com.octo.recon.matching.IborRecord
import com.octo.recon.matching.persistence.ReconciliationStore
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
    val lookups = mutableListOf<Pair<UUID, TenantScope>>()

    override fun iborRecords(
        tenantId: UUID,
        sourceSystem: String,
        zone: ZoneId,
        scope: TenantScope,
    ) = ledger.filter { it.sourceSystem == sourceSystem }.also { lookups += tenantId to scope }

    override fun existingTask(
        tenantId: UUID,
        brk: Break,
        scope: TenantScope,
    ) = rows.firstOrNull { it.taskId != null && it.brk.sameKey(brk) }?.taskId

    override fun record(
        tenantId: UUID,
        runId: UUID,
        brk: Break,
        taskId: UUID?,
        correlationId: UUID,
        scope: TenantScope,
    ): UUID {
        if (taskId != null && existingTask(tenantId, brk, scope) != null) throw SQLException("reconciliation_break_one_task", "23505")
        rows += Row(runId, brk, taskId)
        return UUID.randomUUID()
    }

    private fun Break.sameKey(other: Break) =
        kind == other.kind && sourceSystem == other.sourceSystem && sourceRef == other.sourceRef && ledgerEventId == other.ledgerEventId
}
