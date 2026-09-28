package com.octo.api

import com.octo.persistence.TenantRoutingContext
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.core.env.Environment
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import javax.sql.DataSource

/**
 * The bridge-tier seam (ADR-0007): a routing wrapper around the primary [DataSource]. When
 * `TenantRoutingContext` binds a datasource key the connection comes from the keyed target —
 * a bridge tenant's dedicated database — otherwise the shared pool serves, which is every
 * request today. Keys absent from [targets] fall back to the default rather than erroring:
 * a mis-keyed context degrades to pool isolation, never cross-tenant (the pool's own RLS and
 * tenant-scoped queries still constrain every row a bridge context might read there).
 */
class TenantRoutingDataSource(
    defaultTarget: DataSource,
    targets: Map<String, DataSource>,
) : AbstractRoutingDataSource() {
    init {
        setDefaultTargetDataSource(defaultTarget)
        setTargetDataSources(targets.mapKeys { it.key as Any })
        setLenientFallback(true)
        afterPropertiesSet()
    }

    override fun determineCurrentLookupKey(): Any? = TenantRoutingContext.key()
}

/**
 * Loads `OCTO_DS_*` connection triples into named datasources. `OCTO_TENANT_DATASOURCES`
 * lists the keys (`bridge-acme,bridge-sovereign`); each key takes `OCTO_DS_{KEY}_URL`,
 * `OCTO_DS_{KEY}_USER`, `OCTO_DS_{KEY}_PASSWORD`. A listed key missing any of the three
 * fails startup — a half-provisioned placement is a boot error, not a runtime surprise.
 */
object TenantDatasourceRegistry {
    fun load(env: Environment): Map<String, DataSource> {
        val keys =
            env
                .getProperty("OCTO_TENANT_DATASOURCES")
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?: return emptyMap()
        return keys.associateWith { key ->
            val prefix = "OCTO_DS_${envName(key)}"
            val url = env.getRequiredProperty("${prefix}_URL")
            val config =
                HikariConfig().apply {
                    jdbcUrl = url
                    username = env.getRequiredProperty("${prefix}_USER")
                    password = env.getRequiredProperty("${prefix}_PASSWORD")
                    // Placements are few and per-tenant — small pools keep total connections sane.
                    maximumPoolSize = env.getProperty("${prefix}_POOL_SIZE", "5").toInt()
                    poolName = "octo-ds-$key"
                }
            HikariDataSource(config)
        }
    }

    /** `bridge-acme` → `BRIDGE_ACME` for the env-var names. */
    private fun envName(key: String): String = key.uppercase().replace(Regex("[^A-Z0-9]"), "_")
}
