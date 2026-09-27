package com.octo.api.compliance

import com.octo.recon.compliance.ComplianceInputs
import com.octo.recon.compliance.Evaluation
import com.octo.recon.compliance.Result
import com.octo.recon.compliance.evaluate
import com.octo.recon.compliance.persistence.ComplianceStore
import com.octo.recon.persistence.TenantScope
import com.octo.workflow.Task
import com.octo.workflow.TaskKind
import com.octo.workflow.persistence.TaskProvenance
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** Opens the review task a breach needs; `JdbcTaskStore.create` behind it in production. */
fun interface TaskOpener {
    fun open(
        task: Task,
        provenance: TaskProvenance,
    )
}

/** One rule's evaluation and, for a breach, the task that reviews it. [recorded] is false when an earlier run already holds the breach. */
data class Outcome(
    val evaluation: Evaluation,
    val taskId: UUID?,
    val recorded: Boolean,
)

/**
 * Runs a tenant's active rules over one subject (#106, #6 slice 8) and turns each breach into exactly one
 * `workflow_task` of kind `review` with subject `compliance-breach`. A breach blocks nothing: the task's
 * decision is the control. Re-running the same date finds the earlier task instead of opening another,
 * and V14's `compliance_breach_once` index is the backstop for two runners racing on one breach.
 */
class ComplianceRunner(
    private val store: ComplianceStore,
    private val tasks: TaskOpener,
) {
    fun run(
        tenantId: UUID,
        inputs: ComplianceInputs,
        requestedBy: String,
        correlationId: UUID,
    ): List<Outcome> {
        val scope = TenantScope.Tenants(listOf(tenantId))
        return evaluate(store.activeRules(tenantId, scope), inputs).map { evaluation ->
            if (evaluation.result != Result.BREACH) {
                store.record(tenantId, evaluation, null, correlationId, scope)
                return@map Outcome(evaluation, null, recorded = true)
            }
            store.breachTask(tenantId, evaluation, scope)?.let { return@map Outcome(evaluation, it, recorded = false) }
            val task = Task(UUID.randomUUID(), TaskKind.REVIEW, "compliance-breach", evaluation.key(), requestedBy, Instant.now())
            tasks.open(task, TaskProvenance("api", correlationId))
            try {
                store.record(tenantId, evaluation, task.id, correlationId, scope)
                Outcome(evaluation, task.id, recorded = true)
            } catch (e: SQLException) {
                // ponytail: the task opened for the losing runner stays open with no evaluation; close it in a sweep if races turn out to be common.
                val winner = store.breachTask(tenantId, evaluation, scope) ?: throw e
                Outcome(evaluation, winner, recorded = false)
            }
        }
    }

    private fun Evaluation.key() = "${rule.id}/$subject/$asOf"
}
