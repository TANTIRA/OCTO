package com.octo.api.graph

import com.octo.workflow.Task
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.opened
import com.octo.workflow.persistence.TaskProvenance
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * #564: every discrepancy opens exactly one `evidence-request` task keyed by its discrepancy key,
 * per-kind gauges refresh on each pass, and a failed open costs a log line — never the report.
 * `reconcileTenant` never touches the DataSource (tenant enumeration is the scheduled path's job),
 * so a never-connected driver stands in.
 */
class GraphReconciliationRunnerTest {
    private val tenantId = UUID.randomUUID()
    private val octoId = UUID.randomUUID()

    private class FakeOpener(
        var fails: Boolean = false,
    ) : GraphTaskOpener {
        val opened = mutableListOf<Task>()

        override fun open(
            task: Task,
            provenance: TaskProvenance,
        ): TaskState {
            if (fails) throw IllegalStateException("database down")
            opened += task
            return opened(task)
        }
    }

    private fun reportOf(vararg discrepancies: GraphDiscrepancy) =
        GraphReconciliation(tenantId, checked = 5, discrepancies = discrepancies.toList())

    @Test
    fun `every discrepancy opens one evidence-request task on its own key`() {
        val opener = FakeOpener()
        val discrepancies =
            listOf(
                GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "asset", octoId, "no node"),
                GraphDiscrepancy(GraphDiscrepancyKind.STALE, "instrument-flow", octoId, "labels differ"),
            )
        val runner =
            GraphReconciliationRunner(
                DriverManagerDataSource("jdbc:postgresql://unused"),
                { reportOf(*discrepancies.toTypedArray()) },
                opener,
                null,
            )

        val report = runner.reconcileTenant(tenantId)

        assertThat(report.discrepancies).hasSize(2)
        assertThat(opener.opened.map { it.kind }).containsOnly(TaskKind.EVIDENCE_REQUEST)
        assertThat(opener.opened.map { it.requestedBy }).containsOnly(GRAPH_RECONCILER_ACTOR)
        assertThat(opener.opened.map { it.subjectType }).containsOnly(GRAPH_DISCREPANCY_SUBJECT)
        assertThat(opener.opened.map { it.subjectId }).containsExactly(
            "$tenantId:missing:asset:$octoId",
            "$tenantId:stale:instrument-flow:$octoId",
        )
    }

    @Test
    fun `the same key is passed twice and dedupe is the store's — gauges drop back to zero when drift clears`() {
        val opener = FakeOpener()
        val meters = SimpleMeterRegistry()
        var drift = true
        val runner =
            GraphReconciliationRunner(
                DriverManagerDataSource("jdbc:postgresql://unused"),
                {
                    if (drift) {
                        reportOf(GraphDiscrepancy(GraphDiscrepancyKind.ORPHAN, "unknown", octoId, "labels {Fund}"))
                    } else {
                        reportOf()
                    }
                },
                opener,
                meters,
            )

        runner.reconcileTenant(tenantId)
        runner.reconcileTenant(tenantId)
        assertThat(opener.opened).hasSize(2) // openUnlessOpen collapses them in production
        assertThat(opener.opened.map { it.subjectId }.distinct()).hasSize(1)
        assertThat(
            meters.get("graph.reconciliation.discrepancies").tags("kind", "orphan").gauge().value(),
        ).isEqualTo(1.0)

        drift = false
        runner.reconcileTenant(tenantId)
        assertThat(
            meters.get("graph.reconciliation.discrepancies").tags("kind", "orphan").gauge().value(),
        ).isZero()
    }

    @Test
    fun `a task open that throws never costs the report`() {
        val opener = FakeOpener(fails = true)
        val runner =
            GraphReconciliationRunner(
                DriverManagerDataSource("jdbc:postgresql://unused"),
                { reportOf(GraphDiscrepancy(GraphDiscrepancyKind.FAILED, "asset", octoId, "attempts 8")) },
                opener,
                null,
            )

        assertThat(runner.reconcileTenant(tenantId).discrepancies).hasSize(1)
    }
}
