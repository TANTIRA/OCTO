package com.octo.api

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * Replaces the auto-configured datasource with the routing wrapper (ADR-0007) whenever a
 * `spring.datasource.url` exists. Slices that exclude persistence define none, and get no
 * datasource bean — identical to the plain auto-configured behavior they had before.
 *
 * The pool binds `spring.datasource.url`/credentials directly (a bare [HikariDataSource] with
 * lazy pool init plus the usual `spring.datasource.hikari.*` properties), the shared schema keeps
 * serving everything, and `OCTO_DS_*` triples register the bridge placements `tenant
 * .datasource_key` can name. Until `TenantRoutingContext` binds a key, the wrapper answers every
 * connection from the pool exactly as the unwrapped datasource did.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["spring.datasource.url"])
class RoutingDataSourceConfiguration {
    /** The shared pool — every pool tenant and every membership/access table lives here. */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    fun poolDataSource(env: Environment): HikariDataSource =
        HikariDataSource().apply {
            jdbcUrl = env.getRequiredProperty("spring.datasource.url")
            username = env.getProperty("spring.datasource.username")
            password = env.getProperty("spring.datasource.password")
        }

    @Bean
    @Primary
    fun dataSource(
        pool: HikariDataSource,
        env: Environment,
    ): DataSource = TenantRoutingDataSource(pool, TenantDatasourceRegistry.load(env))
}
