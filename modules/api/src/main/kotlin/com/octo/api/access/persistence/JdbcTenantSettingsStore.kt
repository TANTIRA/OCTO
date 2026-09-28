package com.octo.api.access.persistence

import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC access to `octo.tenant_setting` (V31). Reads run under the caller's [TenantScope] like every
 * tenant table; [put] upserts the row and appends its `audit_event` in the same transaction — a
 * privileged configuration change commits with its audit trail or not at all.
 */
class JdbcTenantSettingsStore(
    private val dataSource: DataSource,
) : TenantSettings {
    override fun get(
        tenantId: UUID,
        key: String,
        scope: TenantScope,
    ): String? =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    "select value::text from octo.tenant_setting where tenant_id = ? and key = ?",
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setString(2, key)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) null else rows.getString(1)
                    }
                }
        }

    override fun all(
        tenantId: UUID,
        scope: TenantScope,
    ): Map<String, String> =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    "select key, value::text from octo.tenant_setting where tenant_id = ? order by key",
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.executeQuery().use { rows ->
                        buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                    }
                }
        }

    override fun put(
        tenantId: UUID,
        key: String,
        value: String,
        actor: String,
        provenance: AccessProvenance,
        scope: TenantScope,
    ) {
        dataSource.scoped(scope) { connection ->
            upsert(connection, tenantId, key, value, provenance)
            audit(connection, tenantId, key, actor, provenance)
        }
    }

    private fun upsert(
        connection: Connection,
        tenantId: UUID,
        key: String,
        value: String,
        provenance: AccessProvenance,
    ) {
        connection
            .prepareStatement(
                """
                insert into octo.tenant_setting (tenant_id, key, value, source_system, correlation_id)
                values (?, ?, ?::jsonb, ?, ?)
                on conflict (tenant_id, key)
                do update set value = excluded.value, recorded_at = now(),
                              source_system = excluded.source_system, correlation_id = excluded.correlation_id
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, key)
                statement.setString(3, value)
                statement.setString(4, provenance.sourceSystem)
                statement.setObject(5, provenance.correlationId)
                statement.executeUpdate()
            }
    }

    private fun audit(
        connection: Connection,
        tenantId: UUID,
        key: String,
        actor: String,
        provenance: AccessProvenance,
    ) {
        connection
            .prepareStatement(
                """
                insert into octo.audit_event (occurred_at, actor, action, subject_type, subject_id, correlation_id, details)
                values (now(), ?, 'tenant-setting-write', 'tenant_setting', ?, ?, jsonb_build_object('key', ?, 'tenant', ?::text))
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, actor)
                statement.setString(2, tenantId.toString())
                statement.setObject(3, provenance.correlationId)
                statement.setString(4, key)
                statement.setString(5, tenantId.toString())
                statement.executeUpdate()
            }
    }
}
