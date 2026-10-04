package com.octo.api.graph

import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import com.octo.workflow.Task
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.persistence.TaskProvenance
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

internal const val GRAPH_RECONCILER_ACTOR = "graph-reconciler"

/** Workflow subject type for one graph↔ledger discrepancy. Dedup is per subject, so the key lives in the id. */
internal const val GRAPH_DISCREPANCY_SUBJECT = "graph-discrepancy"

/** One evidence-request for a discrepancy. [opened] is false when an earlier task on that key is still open. */
data class GraphDriftTask(
    val discrepancy: GraphDiscrepancy,
    val taskId: UUID,
    val opened: Boolean,
)

/** A tenant's reconciliation and the task reviewing each discrepancy, in the same order. */
data class GraphReconciliationReport(
    val reconciliation: GraphReconciliation,
    val tasks: List<GraphDriftTask>,
)

/** `JdbcTaskStore.openUnlessOpen` in production: one open task per subject and kind. */
fun interface GraphDriftTaskOpener {
    fun openUnlessOpen(
        task: Task,
        provenance: TaskProvenance,
    ): TaskState
}

/** Every tenant a platform sweep may reconcile. `All` is the scope: the caller is the scheduler, not a user. */
interface GraphTenantDirectory {
    fun ids(): List<UUID>

    fun exists(tenantId: UUID): Boolean
}

/** Reconciles one tenant and opens its drift tasks. */
fun interface GraphReconciliationRuns {
    fun run(
        tenantId: UUID,
        correlationId: UUID,
    ): GraphReconciliationReport
}

/** One open evidence-request per tenant, discrepancy kind, aggregate and node. */
internal fun graphDiscrepancyKey(
    tenantId: UUID,
    discrepancy: GraphDiscrepancy,
): String = "$tenantId/${discrepancy.kind.wireValue}/${discrepancy.aggregateType}/${discrepancy.octoId}"

/**
 * Opens the evidence-request a discrepancy needs (#564), the same disposition model as [com.octo.api.reconciliation.ReconciliationRunner].
 * Dedup is [GraphDriftTaskOpener]: a repeat adopts the open task, and a terminal task is history, so drift that
 * remains after someone closes the task is raised again.
 */
class GraphReconciliationService(
    private val reconcile: (UUID) -> GraphReconciliation,
    private val tasks: GraphDriftTaskOpener,
) : GraphReconciliationRuns {
    override fun run(
        tenantId: UUID,
        correlationId: UUID,
    ): GraphReconciliationReport {
        val reconciliation = reconcile(tenantId)
        val provenance = TaskProvenance(GRAPH_RECONCILER_ACTOR, correlationId)
        val openedTasks =
            reconciliation.discrepancies.map { discrepancy ->
                val task =
                    Task(
                        UUID.randomUUID(),
                        TaskKind.EVIDENCE_REQUEST,
                        GRAPH_DISCREPANCY_SUBJECT,
                        graphDiscrepancyKey(tenantId, discrepancy),
                        GRAPH_RECONCILER_ACTOR,
                        Instant.now(),
                    )
                val state = tasks.openUnlessOpen(task, provenance)
                GraphDriftTask(discrepancy, state.task.id, opened = state.task.id == task.id)
            }
        return GraphReconciliationReport(reconciliation, openedTasks)
    }
}

/** `octo.tenant` under [TenantScope.All]. The scheduled sweep and the admin read both start from this list. */
class JdbcGraphTenantDirectory(
    private val dataSource: DataSource,
) : GraphTenantDirectory {
    override fun ids(): List<UUID> =
        dataSource.scoped(TenantScope.All) { connection ->
            connection.prepareStatement("select id from octo.tenant order by created_at").use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getObject(1, UUID::class.java))
                        }
                    }
                }
            }
        }

    override fun exists(tenantId: UUID): Boolean =
        dataSource.scoped(TenantScope.All) { connection ->
            connection.prepareStatement("select 1 from octo.tenant where id = ?").use { statement ->
                statement.setObject(1, tenantId)
                statement.executeQuery().use { rows -> rows.next() }
            }
        }
}

/**
 * Runs [GraphReconciler] for every tenant on a fixed delay (#564), beside [GraphProjector], and only when a graph
 * is configured. Each discrepancy becomes one evidence-request. Gauges publish after a sweep that finished every
 * tenant; a failure leaves the previous complete sweep in place so a partial pass cannot report zero drift.
 */
class GraphReconciliationScheduler(
    private val tenants: GraphTenantDirectory,
    private val runs: GraphReconciliationRuns,
    meters: MeterRegistry?,
) {
    private val log = LoggerFactory.getLogger(GraphReconciliationScheduler::class.java)
    private val counts = GraphDiscrepancyKind.entries.associateWith { AtomicLong() }
    private val failures = meters?.let { Counter.builder("graph.reconciliation.failures").register(it) }

    init {
        if (meters != null) {
            for (kind in GraphDiscrepancyKind.entries) {
                Gauge
                    .builder("graph.reconciliation.discrepancies", counts.getValue(kind)) { it.get().toDouble() }
                    .tag("kind", kind.wireValue)
                    .description("Graph-ledger discrepancies on the last complete sweep")
                    .register(meters)
            }
        }
    }

    @Scheduled(
        fixedDelayString = "\${GRAPH_RECONCILIATION_POLL_MS:60000}",
        initialDelayString = "\${GRAPH_RECONCILIATION_INITIAL_DELAY_MS:20000}",
    )
    fun poll() {
        val correlationId = UUID.randomUUID()
        val ids =
            try {
                tenants.ids()
            } catch (e: Exception) {
                failures?.increment()
                log.warn("graph reconciliation could not list tenants: {}", e.javaClass.simpleName)
                return
            }
        val totals = GraphDiscrepancyKind.entries.associateWith { 0L }.toMutableMap()
        var failed = false
        for (tenantId in ids) {
            try {
                val report = runs.run(tenantId, correlationId)
                for (discrepancy in report.reconciliation.discrepancies) {
                    totals[discrepancy.kind] = totals.getValue(discrepancy.kind) + 1
                }
            } catch (e: Exception) {
                failed = true
                failures?.increment()
                log.warn("graph reconciliation failed for tenant {}: {}", tenantId, e.javaClass.simpleName)
            }
        }
        if (!failed) {
            totals.forEach { (kind, count) -> counts.getValue(kind).set(count) }
        }
    }
}
