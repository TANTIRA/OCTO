package com.mesta.asset.api.reconciliation

import com.mesta.asset.recon.matching.Break
import com.mesta.asset.recon.matching.SourceRecord
import com.mesta.asset.recon.matching.Tolerance
import com.mesta.asset.recon.matching.persistence.ReconciliationStore
import com.mesta.asset.recon.matching.reconcile
import com.mesta.asset.workflow.Task
import com.mesta.asset.workflow.TaskKind
import com.mesta.asset.workflow.persistence.TaskProvenance
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Opens the evidence-request task a break needs; `JdbcTaskStore.create` behind it in production. */
fun interface BreakTaskOpener {
    fun open(
        task: Task,
        provenance: TaskProvenance,
    )
}

/** One break of a run and the evidence-request task reviewing it. [opened] is false when an earlier run's task is reused. */
data class BreakOutcome(
    val brk: Break,
    val taskId: UUID,
    val opened: Boolean,
)

data class RunResult(
    val runId: UUID,
    val matched: Int,
    val outcomes: List<BreakOutcome>,
)

/**
 * Runs one reconciliation (#107, #6 slice 9): the source records against the current ledger events of their source
 * systems, every break recorded, and exactly one `workflow_task` of kind `evidence-request` per break key across
 * runs. A break blocks nothing: the task's decision is its disposition. V15's index is the backstop when two
 * runners race on one break.
 */
class ReconciliationRunner(
    private val store: ReconciliationStore,
    private val tasks: BreakTaskOpener,
) {
    fun run(
        tenantId: UUID,
        source: List<SourceRecord>,
        tolerance: Tolerance,
        zone: ZoneId,
        requestedBy: String,
        correlationId: UUID,
    ): RunResult {
        val ibor = source.map { it.sourceSystem }.toSet().flatMap { store.iborRecords(it, zone) }
        val result = reconcile(source, ibor, tolerance)
        val runId = UUID.randomUUID()
        val outcomes =
            result.breaks.map { brk ->
                store.existingTask(tenantId, brk)?.let { existing ->
                    store.record(tenantId, runId, brk, null, correlationId)
                    return@map BreakOutcome(brk, existing, opened = false)
                }
                val task = Task(UUID.randomUUID(), TaskKind.EVIDENCE_REQUEST, "reconciliation-break", brk.key(), requestedBy, Instant.now())
                tasks.open(task, TaskProvenance("api", correlationId))
                try {
                    store.record(tenantId, runId, brk, task.id, correlationId)
                    BreakOutcome(brk, task.id, opened = true)
                } catch (e: SQLException) {
                    // ponytail: the task opened for the losing runner stays open with no break; close it in a sweep if races turn out to be common.
                    val winner = store.existingTask(tenantId, brk) ?: throw e
                    store.record(tenantId, runId, brk, null, correlationId)
                    BreakOutcome(brk, winner, opened = false)
                }
            }
        return RunResult(runId, result.matched.size, outcomes)
    }

    private fun Break.key() = "${kind.wireValue}/$sourceSystem/${sourceRef ?: "-"}/${ledgerEventId ?: "-"}"
}
