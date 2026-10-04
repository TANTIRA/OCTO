package com.octo.api.graph

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.function.Supplier
import javax.sql.DataSource

/** The projector and the reconciliation schedule exist together, and only when `NEO4J_URI` is set. */
class GraphConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(GraphConfiguration::class.java)
            .withBean(
                DataSource::class.java,
                Supplier { DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/octo", "octo", "octo") },
            )

    @Test
    fun `a blank NEO4J_URI leaves the driver and the schedule unwired`() {
        runner.withPropertyValues("NEO4J_URI=").run { context ->
            assertThat(context).doesNotHaveBean(Driver::class.java)
            assertThat(context).doesNotHaveBean(GraphReconciliationScheduler::class.java)
            assertThat(context).doesNotHaveBean(GraphReconciler::class.java)
            assertThat(context).doesNotHaveBean(GraphProjector::class.java)
        }
    }

    @Test
    fun `a configured graph wires the reconciler, the schedule, and a gauge per kind`() {
        val meters = SimpleMeterRegistry()
        runner
            .withBean(MeterRegistry::class.java, Supplier { meters })
            .withPropertyValues(
                "NEO4J_URI=bolt://127.0.0.1:9",
                "NEO4J_USER=neo4j",
                "NEO4J_PASSWORD=neo4j",
                "GRAPH_PROJECTOR_INITIAL_DELAY_MS=3600000",
                "GRAPH_PROJECTOR_POLL_MS=3600000",
                "GRAPH_RECONCILIATION_INITIAL_DELAY_MS=3600000",
                "GRAPH_RECONCILIATION_POLL_MS=3600000",
            ).run { context ->
                assertThat(context).hasSingleBean(Driver::class.java)
                assertThat(context).hasSingleBean(GraphProjector::class.java)
                assertThat(context).hasSingleBean(GraphReconciler::class.java)
                assertThat(context).hasSingleBean(GraphReconciliationScheduler::class.java)
                assertThat(context).hasSingleBean(GraphReconciliationRuns::class.java)
                GraphDiscrepancyKind.entries.forEach { kind ->
                    assertThat(meters.get("graph.reconciliation.discrepancies").tag("kind", kind.wireValue).gauge()).isNotNull
                }
            }
    }
}
