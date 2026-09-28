package com.octo.api

import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.access.persistence.JdbcTenantSettingsStore
import com.octo.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * `JdbcTenantSettingsStore` against the real schema: a setting write must land its row and the
 * `audit_event` row in one commit, and a re-write must upsert (current state, not history — the
 * history is the audit trail). Runs as the table owner; the RLS side is covered in EntityScopingIT.
 * Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantSettingsIT {
    private val tenantId = UUID.randomUUID()
    private val runId = UUID.randomUUID()

    private val dataSource by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("octo")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    private val store by lazy { JdbcTenantSettingsStore(dataSource) }

    @BeforeEach
    fun seed() {
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "insert into octo.tenant (id, slug, display_name, source_system, correlation_id) " +
                        "values ('$tenantId', 't-${tenantId.toString().take(8)}', 'T', 'test', gen_random_uuid())",
                )
            }
        }
    }

    @Test
    fun `put upserts the row and lands its audit event in the same commit`() {
        store.put(tenantId, "rate_limit_per_minute", "60", "admin", provenance(), TenantScope.All)
        store.put(tenantId, "rate_limit_per_minute", "120", "admin", provenance(), TenantScope.All)
        store.put(tenantId, "features.screening_dd", "true", "admin", provenance(), TenantScope.All)

        // Current state: one row per key, latest value wins.
        assertThat(store.get(tenantId, "rate_limit_per_minute", TenantScope.All)).isEqualTo("120")
        assertThat(store.all(tenantId, TenantScope.All))
            .containsEntry("rate_limit_per_minute", "120")
            .containsEntry("features.screening_dd", "true")
        assertThat(store.get(tenantId, "unset_key", TenantScope.All)).isNull()

        // Every write is audited — three writes, three events for this tenant.
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "select count(*) from octo.audit_event " +
                        "where subject_type = 'tenant_setting' and subject_id = ?",
                ).use { s ->
                    s.setString(1, tenantId.toString())
                    s.executeQuery().use { rs ->
                        rs.next()
                        assertThat(rs.getInt(1)).isEqualTo(3)
                    }
                }
        }
    }

    private fun provenance() = AccessProvenance("test", UUID.randomUUID())

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
