package com.octo.api.graph

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
 * Wires the graph projection and its reconciliation (ADR-0004 amendment, #308, #564). Everything hangs off a
 * non-blank `NEO4J_URI`: unset or empty means no driver, no projector and no reconciliation schedule, while store
 * calls keep enqueueing — the outbox waits until a graph is configured. Blank counts as unset on purpose: a compose
 * file that forwards an empty value must not boot a half-configured driver.
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
        neo4jDatabase(env),
        meters.getIfAvailable(),
    )

    @Bean
    @ConditionalOnExpression("!'\${NEO4J_URI:}'.isBlank()")
    fun graphReconciler(
        driver: Driver,
        dataSource: ObjectProvider<DataSource>,
        env: Environment,
    ) = GraphReconciler(dataSource.getObject(), driver, neo4jDatabase(env))

    @Bean
    @ConditionalOnExpression("!'\${NEO4J_URI:}'.isBlank()")
    fun graphTenantDirectory(dataSource: ObjectProvider<DataSource>): GraphTenantDirectory =
        JdbcGraphTenantDirectory(dataSource.getObject())

    @Bean
    @ConditionalOnExpression("!'\${NEO4J_URI:}'.isBlank()")
    fun graphReconciliationRuns(
        reconciler: GraphReconciler,
        dataSource: ObjectProvider<DataSource>,
    ): GraphReconciliationRuns {
        val tasks by lazy { JdbcTaskStore(dataSource.getObject()) }
        return GraphReconciliationService(
            reconciler::reconcile,
            GraphDriftTaskOpener { task, provenance -> tasks.openUnlessOpen(task, provenance) },
        )
    }

    @Bean
    @ConditionalOnExpression("!'\${NEO4J_URI:}'.isBlank()")
    fun graphReconciliationScheduler(
        tenants: GraphTenantDirectory,
        runs: GraphReconciliationRuns,
        meters: ObjectProvider<MeterRegistry>,
    ) = GraphReconciliationScheduler(tenants, runs, meters.getIfAvailable())

    private fun neo4jDatabase(env: Environment) = env.getProperty("NEO4J_DATABASE")?.takeIf(String::isNotBlank) ?: "neo4j"
}
