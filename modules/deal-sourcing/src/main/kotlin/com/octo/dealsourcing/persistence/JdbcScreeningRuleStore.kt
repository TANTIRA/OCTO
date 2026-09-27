package com.octo.dealsourcing.persistence

import com.octo.persistence.TenantScope
import com.octo.persistence.admits
import com.octo.persistence.scoped
import java.sql.Connection
import com.octo.dealsourcing.TenantScope
import com.octo.dealsourcing.scoped
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/** One versioned rule row: the `criteria` column stays raw JSON — the api edge parses it. */
data class ScreeningRuleRow(
    val ruleId: String,
    val version: Int,
    val name: String,
    val criteria: String,
)

/**
 * JDBC access to `mesta.screening_rule` (V20). Rules are versioned per tenant: [define] writes the
 * next version of a `rule_id`, and [activeRules] returns the newest active version of every rule —
 * the set a screen evaluates conjunctively.
 */
class JdbcScreeningRuleStore(
    private val dataSource: DataSource,
) {
    /**
     * Appends version `max(version)+1` of [ruleId] for the tenant; older versions stay for audit.
     * The `(tenant, rule)` advisory lock serializes two definers so a concurrent write can never
     * race the version read into a unique-violation 500.
     */
    /** Appends version `max(version)+1` of [ruleId] for the tenant; older versions stay for audit. */
    fun define(
        tenantId: UUID,
        ruleId: String,
        name: String,
        criteria: String,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ): Int {
        require(scope.admits(tenantId)) { "tenant $tenantId is outside the scoped tenants" }
        return dataSource.scoped(scope) { connection ->
            lockRule(connection, tenantId, ruleId)
    ): Int =
        dataSource.scoped(scope) { connection ->
            val version =
                connection
                    .prepareStatement(
                        "select coalesce(max(version), 0) + 1 from mesta.screening_rule where tenant_id = ? and rule_id = ?",
                    ).use { statement ->
                        statement.setObject(1, tenantId)
                        statement.setString(2, ruleId)
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
            connection
                .prepareStatement(
                    """
                    insert into mesta.screening_rule (tenant_id, rule_id, version, name, criteria, actor, correlation_id)
                    values (?, ?, ?, ?, ?::jsonb, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setString(2, ruleId)
                    statement.setInt(3, version)
                    statement.setString(4, name)
                    statement.setString(5, criteria)
                    statement.setString(6, actor)
                    statement.setObject(7, provenance.correlationId)
                    statement.executeUpdate()
                }
            version
        }
    }

    /**
     * Appends an inactive version of [ruleId] — the append-only way a rule leaves the active set:
     * the row is a new version like any other, so the criteria that retired stay auditable and a
     * later [define] re-activates the rule. Returns the version written, the current version when
     * the rule is already retired (idempotent), or null when the tenant holds no such rule.
     */
    fun retire(
        tenantId: UUID,
        ruleId: String,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ): Int? {
        require(scope.admits(tenantId)) { "tenant $tenantId is outside the scoped tenants" }
        return dataSource.scoped(scope) { connection ->
            lockRule(connection, tenantId, ruleId)
            val latest =
                connection
                    .prepareStatement(
                        "select version, name, criteria::text, active from mesta.screening_rule where tenant_id = ? and rule_id = ? order by version desc limit 1",
                    ).use { statement ->
                        statement.setObject(1, tenantId)
                        statement.setString(2, ruleId)
                        statement.executeQuery().use { rows ->
                            if (!rows.next()) return@scoped null
                            Latest(
                                version = rows.getInt(1),
                                name = rows.getString(2),
                                criteria = rows.getString(3),
                                active = rows.getBoolean(4),
                            )
                        }
                    }
            if (!latest.active) return@scoped latest.version
            val version = latest.version + 1
            connection
                .prepareStatement(
                    """
                    insert into mesta.screening_rule (tenant_id, rule_id, version, name, criteria, active, actor, correlation_id)
                    values (?, ?, ?, ?, ?::jsonb, false, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setString(2, ruleId)
                    statement.setInt(3, version)
                    statement.setString(4, latest.name)
                    statement.setString(5, latest.criteria)
                    statement.setString(6, actor)
                    statement.setObject(7, provenance.correlationId)
                    statement.executeUpdate()
                }
            version
        }
    }

    /**
     * The newest version of every `rule_id` the tenant holds, keeping only the ones still active —
     * the screen's rule set. The `distinct on` picks the newest version first; a retire tombstone
     * therefore removes the rule rather than resurrecting its last active version.
     */
    fun activeRules(
        tenantId: UUID,
        scope: TenantScope,
    ): List<ScreeningRuleRow> {
        if (!scope.admits(tenantId)) return emptyList()
        return dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    """
                    select rule_id, version, name, criteria::text
                    from (
                        select distinct on (rule_id) rule_id, version, name, criteria, active
                        from mesta.screening_rule
                        where tenant_id = ?
                        order by rule_id, version desc
                    ) latest
                    where active

    /** The newest active version of every `rule_id` the tenant holds — the screen's rule set. */
    fun activeRules(
        tenantId: UUID,
        scope: TenantScope,
    ): List<ScreeningRuleRow> =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    """
                    select distinct on (rule_id) rule_id, version, name, criteria::text
                    from mesta.screening_rule
                    where tenant_id = ? and active
                    order by rule_id, version desc
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.toRuleRow())
                            }
                        }
                    }
                }
        }
    }

    private class Latest(
        val version: Int,
        val name: String,
        val criteria: String,
        val active: Boolean,
    )

    private fun lockRule(
        connection: Connection,
        tenantId: UUID,
        ruleId: String,
    ) {
        connection
            .prepareStatement(
                "select pg_advisory_xact_lock(hashtextextended('mesta.screening_rule:' || ?::text || ':' || ?::text, 0))",
            ).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, ruleId)
                statement.executeQuery().close()
            }
    }

    private fun ResultSet.toRuleRow() =
        ScreeningRuleRow(
            ruleId = getString(1),
            version = getInt(2),
            name = getString(3),
            criteria = getString(4),
        )
}
