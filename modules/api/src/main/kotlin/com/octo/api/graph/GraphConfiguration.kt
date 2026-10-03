package com.octo.api.graph

import com.octo.api.access.PlatformAdmin
import com.octo.workflow.persistence.JdbcTaskStore
import io.micrometer.core.instrument.MeterRegistry
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

/**
 * Wires the graph projection (ADR-0004 amendment, #308). Everything hangs off a non-blank `NEO4J_URI`: unset or empty
 * means no driver and no projector, while store calls keep enqueueing — the outbox waits until a graph is configured.
 * Blank counts as unset on purpose: a compose file that forwards an empty value must not boot a half-configured driver.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class GraphConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'\${NEO4J_URI:}'.isBlank()")
    fun graphDriver(env: Environment): Driver =
        GraphDatabase.driver(
            env.getRequiredProperty("NEO4J_URI"),
            AuthTokens.basic(env.getRequiredProperty("NEO4J_USER"), env.getRequiredProperty("NEO4J_PASSWORD")),
        )

    @Bean
    @ConditionalOnExpression("!'\${NEO4J_URI:}'.isBlank()")
    fun graphProjector(
        driver: Driver,
        dataSource: ObjectProvider<DataSource>,
        env: Environment,
        meters: ObjectProvider<MeterRegistry>,
    ) = GraphProjector(
        JdbcGraphOutboxStore(dataSource.getObject()),
        driver,
        env.getProperty("NEO4J_DATABASE")?.takeIf(String::isNotBlank) ?: "neo4j",
        meters.getIfAvailable(),
    )

    private fun graphDatabase(env: Environment) = env.getProperty("NEO4J_DATABASE")?.takeIf(String::isNotBlank) ?: "neo4j"

    @Bean
    @ConditionalOnExpression("!'\${NEO4J_URI:}'.isBlank()")
    fun graphReconciler(
        driver: Driver,
        dataSource: ObjectProvider<DataSource>,
        env: Environment,
    ) = GraphReconciler(dataSource.getObject(), driver, graphDatabase(env))

    /**
     * #564: the scheduled per-tenant pass and the admin endpoint it serves. Task opens go through
     * `JdbcTaskStore.openUnlessOpen`, so a discrepancy key already under review is never doubled —
     * the same deduplication `ReconciliationRunner` relies on for ledger breaks.
     */
    @Bean
    @ConditionalOnExpression("!'\${NEO4J_URI:}'.isBlank()")
    fun graphReconciliationRunner(
        reconciler: GraphReconciler,
        dataSource: ObjectProvider<DataSource>,
        meters: ObjectProvider<MeterRegistry>,
    ): GraphReconciliationRunner {
        val tasks = JdbcTaskStore(dataSource.getObject())
        return GraphReconciliationRunner(
            dataSource.getObject(),
            reconciler::reconcile,
            GraphTaskOpener { task, provenance -> tasks.openUnlessOpen(task, provenance) },
            meters.getIfAvailable(),
        )
    }
}
