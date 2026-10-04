package com.octo.api.graph

import com.octo.workflow.Task
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.opened
import com.octo.workflow.persistence.TaskProvenance
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import java.util.UUID

class GraphReconciliationRunTest {
    private val tenantId = UUID.randomUUID()
    private val octoId = UUID.randomUUID()
    private val missing = GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "asset", octoId, "no node")
    private val stale = GraphDiscrepancy(GraphDiscrepancyKind.STALE, "asset", octoId, "legalName")

    private class RecordingOpener : GraphDriftTaskOpener {
        val tasks = mutableListOf<Task>()
        val provenances = mutableListOf<TaskProvenance>()
        var existing: TaskState? = null

        override fun openUnlessOpen(
            task: Task,
            provenance: TaskProvenance,
        ): TaskState {
            tasks += task
            provenances += provenance
            return existing ?: opened(task)
        }
    }

    @Test
    fun `each discrepancy opens one evidence-request, and a repeat adopts the open task`() {
        val opener = RecordingOpener()
        val correlationId = UUID.randomUUID()
        val service =
            GraphReconciliationService(
                { GraphReconciliation(tenantId, 1, listOf(missing, stale)) },
                opener,
            )

        val first = service.run(tenantId, correlationId)

        assertThat(first.reconciliation.clean).isFalse()
        assertThat(first.tasks).allMatch { it.opened }
        assertThat(first.tasks.map { it.taskId }).containsExactlyElementsOf(opener.tasks.map { it.id })
        assertThat(opener.tasks.map { it.kind }).containsOnly(TaskKind.EVIDENCE_REQUEST)
        assertThat(opener.tasks.map { it.subjectType }).containsOnly(GRAPH_DISCREPANCY_SUBJECT)
        assertThat(opener.tasks.map { it.requestedBy }).containsOnly(GRAPH_RECONCILER_ACTOR)
        assertThat(opener.tasks.map { it.subjectId }).containsExactly(
            graphDiscrepancyKey(tenantId, missing),
            graphDiscrepancyKey(tenantId, stale),
        )
        assertThat(opener.provenances).allMatch { it.sourceSystem == GRAPH_RECONCILER_ACTOR && it.correlationId == correlationId }
        assertThat(graphDiscrepancyKey(tenantId, missing)).isNotEqualTo(graphDiscrepancyKey(tenantId, stale))

        opener.existing = opened(opener.tasks.first())
        val again = service.run(tenantId, UUID.randomUUID())
        assertThat(again.tasks).noneMatch { it.opened }
        assertThat(again.tasks.map { it.taskId }).containsOnly(opener.tasks.first().id)
    }

    @Test
    fun `a clean tenant opens nothing`() {
        val opener = RecordingOpener()
        val report =
            GraphReconciliationService({ GraphReconciliation(tenantId, 3, emptyList()) }, opener)
                .run(tenantId, UUID.randomUUID())
        assertThat(report.reconciliation.clean).isTrue()
        assertThat(report.tasks).isEmpty()
        assertThat(opener.tasks).isEmpty()
    }

    @Test
    fun `poll is a fixed-delay schedule`() {
        val scheduled = GraphReconciliationScheduler::class.java.getMethod("poll").getAnnotation(Scheduled::class.java)
        assertThat(scheduled.fixedDelayString).isEqualTo("\${GRAPH_RECONCILIATION_POLL_MS:60000}")
        assertThat(scheduled.initialDelayString).isEqualTo("\${GRAPH_RECONCILIATION_INITIAL_DELAY_MS:20000}")
    }

    @Test
    fun `a complete sweep gauges discrepancies per kind, and a later failure keeps that sweep`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        var failSecond = false
        val calls = mutableListOf<UUID>()
        val runs =
            GraphReconciliationRuns { tenantId, _ ->
                calls += tenantId
                if (failSecond && tenantId == second) error("neo4j down")
                val discrepancies =
                    when (tenantId) {
                        first -> listOf(GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "asset", UUID.randomUUID(), "no node"))
                        else -> listOf(GraphDiscrepancy(GraphDiscrepancyKind.STALE, "asset", UUID.randomUUID(), "name"))
                    }
                GraphReconciliationReport(GraphReconciliation(tenantId, 1, discrepancies), emptyList())
            }
        val meters = SimpleMeterRegistry()
        val scheduler = GraphReconciliationScheduler(directory(first, second), runs, meters)

        scheduler.poll()
        assertThat(calls).containsExactly(first, second)
        assertThat(gauge(meters, "missing")).isEqualTo(1.0)
        assertThat(gauge(meters, "stale")).isEqualTo(1.0)
        assertThat(gauge(meters, "orphan")).isEqualTo(0.0)
        assertThat(gauge(meters, "forked")).isEqualTo(0.0)
        assertThat(gauge(meters, "failed")).isEqualTo(0.0)
        assertThat(gauge(meters, "stuck")).isEqualTo(0.0)

        failSecond = true
        scheduler.poll()
        assertThat(gauge(meters, "missing")).isEqualTo(1.0)
        assertThat(gauge(meters, "stale")).isEqualTo(1.0)
        assertThat(meters.counter("graph.reconciliation.failures").count()).isEqualTo(1.0)
    }

    @Test
    fun `a sweep that cannot list tenants keeps the previous gauge`() {
        val tenant = UUID.randomUUID()
        var listed = false
        val meters = SimpleMeterRegistry()
        val scheduler =
            GraphReconciliationScheduler(
                object : GraphTenantDirectory {
                    override fun ids(): List<UUID> {
                        if (listed) error("database down")
                        listed = true
                        return listOf(tenant)
                    }

                    override fun exists(tenantId: UUID) = tenantId == tenant
                },
                GraphReconciliationRuns { id, _ ->
                    val discrepancy = GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "asset", UUID.randomUUID(), "no node")
                    GraphReconciliationReport(GraphReconciliation(id, 1, listOf(discrepancy)), emptyList())
                },
                meters,
            )
        scheduler.poll()
        assertThat(gauge(meters, "missing")).isEqualTo(1.0)
        scheduler.poll()
        assertThat(gauge(meters, "missing")).isEqualTo(1.0)
        assertThat(meters.counter("graph.reconciliation.failures").count()).isEqualTo(1.0)
    }

    @Test
    fun `a clean sweep after drift publishes zeros`() {
        val tenant = UUID.randomUUID()
        var drift = true
        val runs =
            GraphReconciliationRuns { id, _ ->
                val discrepancies =
                    if (drift) {
                        listOf(
                            GraphDiscrepancy(GraphDiscrepancyKind.ORPHAN, "unknown", UUID.randomUUID(), "labels"),
                        )
                    } else {
                        emptyList()
                    }
                GraphReconciliationReport(GraphReconciliation(id, 1, discrepancies), emptyList())
            }
        val meters = SimpleMeterRegistry()
        val scheduler = GraphReconciliationScheduler(directory(tenant), runs, meters)
        scheduler.poll()
        assertThat(gauge(meters, "orphan")).isEqualTo(1.0)
        drift = false
        scheduler.poll()
        assertThat(gauge(meters, "orphan")).isEqualTo(0.0)
    }

    private fun directory(vararg ids: UUID) =
        object : GraphTenantDirectory {
            override fun ids() = ids.toList()

            override fun exists(tenantId: UUID) = tenantId in ids
        }

    private fun gauge(
        meters: SimpleMeterRegistry,
        kind: String,
    ) = meters
        .get("graph.reconciliation.discrepancies")
        .tag("kind", kind)
        .gauge()
        .value()
}
