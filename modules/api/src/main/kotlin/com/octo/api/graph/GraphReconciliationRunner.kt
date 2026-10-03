package com.octo.api.graph

import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import com.octo.workflow.Task
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.persistence.TaskProvenance
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * The subject every drift task is opened on. The subject id is the discrepancy key —
 * tenant, kind, aggregate and octoId — so [com.octo.workflow.persistence.JdbcTaskStore.openUnlessOpen]
 * deduplicates repeated reconciliations of the same drift and two tenants can never collide.
 */
const val GRAPH_DISCREPANCY_SUBJECT = "graph-discrepancy"

/** Who every reconciliation task records as its requester. */
const val GRAPH_RECONCILER_ACTOR = "graph-reconciler"

/**
 * Opens one workflow task for a discrepancy key. Production wraps
 * `JdbcTaskStore.openUnlessOpen`; the seam keeps the runner unit-testable without Postgres.
 */
fun interface GraphTaskOpener {
    fun open(
        task: Task,
        provenance: TaskProvenance,
    ): TaskState
}

/**
 * Runs [GraphReconciler] for every tenant on a schedule — alongside [GraphProjector], so only
 * when `NEO4J_URI` is configured — and gives each discrepancy the same disposition
 * `ReconciliationRunner` gives a ledger break: one deduplicated `evidence-request` task per key
 * (#564). Postgres remains the ledger of record, so a graph outage never blocks ledger writes;
 * a tenant whose reconcile throws is logged and skipped, and the next pass retries it.
 *
 * `graph.reconciliation.discrepancies` is a per-kind, per-tenant gauge refreshed after each
 * tenant's pass — it answers "is anything drifting" without scraping task tables.
 */
class GraphReconciliationRunner(
    private val dataSource: DataSource,
    private val reconcile: (UUID) -> GraphReconciliation,
    private val opener: GraphTaskOpener,
    private val meters: MeterRegistry?,
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(GraphReconciliationRunner::class.java)
    private val gauges = ConcurrentHashMap<UUID, Map<GraphDiscrepancyKind, AtomicLong>>()

    @Scheduled(
        fixedDelayString = "\${GRAPH_RECONCILIATION_POLL_MS:300000}",
        initialDelayString = "\${GRAPH_RECONCILIATION_INITIAL_DELAY_MS:30000}",
    )
    fun poll() {
        val tenants =
            try {
                tenantIds()
            } catch (e: Exception) {
                log.warn("graph reconciliation could not list tenants: {}", e.message)
                return
            }
        for (tenantId in tenants) {
            try {
                reconcileTenant(tenantId)
            } catch (e: Exception) {
                log.warn("graph reconciliation failed for tenant {}: {}", tenantId, e.message)
            }
        }
    }

    /**
     * Reconciles [tenantId], refreshes its gauges, and opens one evidence-request task per
     * discrepancy key — `openUnlessOpen` makes the second pass a no-op for drift already under
     * review. A task that fails to open is logged, not raised: the report still matters, and the
     * next pass retries the open.
     */
    fun reconcileTenant(tenantId: UUID): GraphReconciliation {
        val report = reconcile(tenantId)
        val perKind = report.discrepancies.groupingBy { it.kind }.eachCount()
        gaugesFor(tenantId).forEach { (kind, gauge) -> gauge.set(perKind[kind]?.toLong() ?: 0) }
        val provenance = TaskProvenance("api", UUID.randomUUID())
        for (discrepancy in report.discrepancies) {
            val task =
                Task(
                    UUID.randomUUID(),
                    TaskKind.EVIDENCE_REQUEST,
                    GRAPH_DISCREPANCY_SUBJECT,
                    "$tenantId:${discrepancy.kind.wireValue}:${discrepancy.aggregateType}:${discrepancy.octoId}",
                    GRAPH_RECONCILER_ACTOR,
                    now(),
                )
            runCatching { opener.open(task, provenance) }
                .onFailure { log.warn("could not open task for graph discrepancy {}: {}", task.subjectId, it.message) }
        }
        return report
    }

    private fun tenantIds(): List<UUID> =
        dataSource.scoped(TenantScope.All) { connection ->
            connection.prepareStatement("select id from octo.tenant order by id").use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getObject(1, UUID::class.java))
                        }
                    }
                }
            }
        }

    private fun gaugesFor(tenantId: UUID): Map<GraphDiscrepancyKind, AtomicLong> =
        gauges.computeIfAbsent(tenantId) { id ->
            GraphDiscrepancyKind.entries.associateWith { kind ->
                AtomicLong(0).also { count ->
                    meters?.let {
                        Gauge
                            .builder("graph.reconciliation.discrepancies", count) { it.get().toDouble() }
                            .tags("tenant_id", id.toString(), "kind", kind.wireValue)
                            .register(it)
                    }
                }
            }
        }
}
