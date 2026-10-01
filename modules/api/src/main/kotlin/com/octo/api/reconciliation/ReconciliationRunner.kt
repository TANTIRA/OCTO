package com.octo.api.reconciliation

import com.octo.persistence.TenantScope
import com.octo.recon.matching.Break
import com.octo.recon.matching.SourceRecord
import com.octo.recon.matching.Tolerance
import com.octo.recon.matching.persistence.RECONCILIATION_BATCH_LIMIT
import com.octo.recon.matching.persistence.ReconciliationStore
import com.octo.recon.matching.reconcile
import com.octo.workflow.Task
import com.octo.workflow.TaskKind
import com.octo.workflow.persistence.TaskProvenance
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Opens the evidence-request task a break needs on the break's own transaction; `JdbcTaskStore.create` behind it in production. */
fun interface BreakTaskOpener {
    fun open(
        connection: Connection,
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
 * runs. A break blocks nothing: the task's decision is its disposition. The task and the break that claims it
 * commit in one transaction (#341); V15's index is the backstop when two runners race on one break, and the
 * loser's rollback takes its task with it. Missing-in-source breaks are raised only for a [complete] batch — one the
 * caller asserts is the full record set of its source systems; one batch of a larger set would otherwise flag every
 * event held in the other batches (#492).
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
        complete: Boolean,
    ): RunResult {
        require(source.isNotEmpty() && source.size <= RECONCILIATION_BATCH_LIMIT) {
            "a reconciliation batch holds 1..$RECONCILIATION_BATCH_LIMIT records, got ${source.size}"
        }
        val scope = TenantScope.Tenants(listOf(tenantId))
        val ibor = source.map { it.sourceSystem }.toSet().flatMap { store.iborRecords(tenantId, it, zone, scope) }
        val result = reconcile(source, ibor, tolerance, sourceComplete = complete)
        val runId = UUID.randomUUID()
        val outcomes =
            result.breaks.map { brk ->
                store.existingTask(tenantId, brk, scope)?.let { existing ->
                    store.record(tenantId, runId, brk, null, correlationId, scope)
                    return@map BreakOutcome(brk, existing, opened = false)
                }
                val task = Task(UUID.randomUUID(), TaskKind.EVIDENCE_REQUEST, "reconciliation-break", brk.key(), requestedBy, Instant.now())
                try {
                    store.record(tenantId, runId, brk, task.id, correlationId, scope) { connection ->
                        tasks.open(connection, task, TaskProvenance("api", correlationId))
                    }
                    BreakOutcome(brk, task.id, opened = true)
                } catch (e: SQLException) {
                    val winner = store.existingTask(tenantId, brk, scope) ?: throw e
                    store.record(tenantId, runId, brk, null, correlationId, scope)
                    BreakOutcome(brk, winner, opened = false)
                }
            }
        return RunResult(runId, result.matched.size, outcomes)
    }

    private fun Break.key() = "${kind.wireValue}/$sourceSystem/${sourceRef ?: "-"}/${ledgerEventId ?: "-"}"
}
