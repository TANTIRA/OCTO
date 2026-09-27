package com.octo.dealsourcing.persistence

import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
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
    /** Appends version `max(version)+1` of [ruleId] for the tenant; older versions stay for audit. */
    fun define(
        tenantId: UUID,
        ruleId: String,
        name: String,
        criteria: String,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
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

    private fun ResultSet.toRuleRow() =
        ScreeningRuleRow(
            ruleId = getString(1),
            version = getInt(2),
            name = getString(3),
            criteria = getString(4),
        )
}
