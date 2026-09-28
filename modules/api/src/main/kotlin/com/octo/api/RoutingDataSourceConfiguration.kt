package com.octo.api

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * Replaces the auto-configured datasource with the routing wrapper (ADR-0007). The pool
 * datasource is built from the same `spring.datasource.*` properties Boot would have used —
 * the shared schema keeps serving everything — and `OCTO_DS_*` triples register the bridge
 * placements `tenant.datasource_key` can name. Until `TenantRoutingContext` binds a key, the
 * wrapper answers every connection from the pool exactly as the unwrapped datasource did.
 */
@Configuration(proxyBeanMethods = false)
class RoutingDataSourceConfiguration {
    // Declaring a DataSource bean backs DataSourceAutoConfiguration off entirely, so the
    // properties holder it would have registered is declared here instead (same binding).
    @Bean
    @ConfigurationProperties("spring.datasource")
    fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

    /** The shared pool — every pool tenant and every membership/access table lives here. */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    fun poolDataSource(properties: DataSourceProperties): HikariDataSource =
        properties
            .initializeDataSourceBuilder()
            .type(HikariDataSource::class.java)
            .build()

    @Bean
    @Primary
    fun dataSource(
        pool: HikariDataSource,
        env: Environment,
    ): DataSource = TenantRoutingDataSource(pool, TenantDatasourceRegistry.load(env))
}
