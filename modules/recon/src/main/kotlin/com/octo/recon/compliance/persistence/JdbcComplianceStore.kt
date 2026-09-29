package com.octo.recon.compliance.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.persistence.TenantScope
import com.octo.persistence.admits
import com.octo.persistence.scoped
import com.octo.recon.compliance.ComplianceCheck
import com.octo.recon.compliance.ComplianceRule
import com.octo.recon.compliance.Evaluation
import java.math.BigDecimal
import java.sql.Connection
import java.util.Currency
import java.util.UUID
import javax.sql.DataSource

/** Where a rule or evaluation row came from, for the provenance columns V14 requires. */
data class ComplianceProvenance(
    val actor: String,
    val correlationId: UUID,
)

/** One evaluation run covers at most this many of a tenant's active rules — beyond it the run would write an unbounded row set per call. */
const val EVALUATION_RULE_LIMIT = 500

/** What the runner and endpoints read and write. */
interface ComplianceStore {
    /** The latest active version of every rule of the tenant. */
    fun activeRules(
        tenantId: UUID,
        scope: TenantScope,
    ): List<ComplianceRule>

    /**
     * Appends the next version of [ruleId] for the tenant — the server allocates it, so two
     * definers can never collide on `(tenant, rule, version)`. A non-null [expectedVersion]
     * must equal that next version; a mismatch throws [IllegalStateException] (the api maps
     * it to 409). Returns the version actually written.
     */
    fun defineRule(
        tenantId: UUID,
        ruleId: String,
        name: String,
        check: ComplianceCheck,
        expectedVersion: Int?,
        provenance: ComplianceProvenance,
        scope: TenantScope,
    ): ComplianceRule

    /**
     * Appends an inactive version of [ruleId] — the append-only way a rule leaves the active
     * set: the row is a new version like any other, so the definition that retired stays
     * auditable and a later [defineRule] re-activates it. Returns the tombstone rule, the
     * current version when the rule is already retired (idempotent), or null when the tenant
     * holds no such rule.
     */
    fun retire(
        tenantId: UUID,
        ruleId: String,
        provenance: ComplianceProvenance,
        scope: TenantScope,
    ): ComplianceRule?

    /** The task already opened for this breach on this date, if V14's `compliance_breach_once` already holds a row. */
    fun breachTask(
        tenantId: UUID,
        evaluation: Evaluation,
        scope: TenantScope,
    ): UUID?

    /**
     * Records one evaluation; a breach carries the task it opened. [openTask] inserts that task on the same
     * transaction first, so a record that fails (V14's `compliance_breach_once` included) rolls the task back
     * with it. Returns the row id.
     */
    fun record(
        tenantId: UUID,
        evaluation: Evaluation,
        taskId: UUID?,
        correlationId: UUID,
        scope: TenantScope,
        openTask: ((Connection) -> Unit)? = null,
    ): UUID
}

/**
 * JDBC access to `octo.compliance_rule` and `octo.compliance_evaluation` (V14). A rule's `definition` is the
 * [ComplianceCheck] as json: `{"check": "concentration-limit", "maxFraction": "0.25"}`,
 * `{"check": "currency-exposure-limit", "currency": "EUR", "maxFraction": "0.4"}`, `{"check": "coverage-floor", "minRatio": "1.2"}`.
 */
class JdbcComplianceStore(
    private val dataSource: DataSource,
) : ComplianceStore {
    private val json = ObjectMapper()

    override fun activeRules(
        tenantId: UUID,
        scope: TenantScope,
    ): List<ComplianceRule> {
        if (!scope.admits(tenantId)) return emptyList()
        // distinct on picks the newest version of each rule_id first; filtering active afterwards
        // is what lets a retire tombstone remove the rule instead of resurrecting its last version.
        val sql =
            """
            select rule_id, version, name, definition::text
            from (
                select distinct on (rule_id) rule_id, version, name, definition, active
                from octo.compliance_rule
                where tenant_id = ?
                order by rule_id, version desc
            ) latest
            where active
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows else null }
                        .map {
                            ComplianceRule(
                                it.getString("rule_id"),
                                it.getInt("version"),
                                it.getString("name"),
                                parseCheck(json.readTree(it.getString("definition"))),
                            )
                        }.toList()
                }
            }
        }
    }

    /**
     * Appends version `max(version)+1` of [ruleId] for the tenant; older versions stay for audit.
     * The `(tenant, rule)` advisory lock serializes two definers so a concurrent write can never
     * race the version read into a unique-violation 500 — the `JdbcScreeningRuleStore` contract.
     */
    override fun defineRule(
        tenantId: UUID,
        ruleId: String,
        name: String,
        check: ComplianceCheck,
        expectedVersion: Int?,
        provenance: ComplianceProvenance,
        scope: TenantScope,
    ): ComplianceRule {
        require(scope.admits(tenantId)) { "tenant $tenantId is outside the scoped tenants" }
        return dataSource.scoped(scope) { connection ->
            lockRule(connection, tenantId, ruleId)
            val version =
                connection
                    .prepareStatement(
                        "select coalesce(max(version), 0) + 1 from octo.compliance_rule where tenant_id = ? and rule_id = ?",
                    ).use { statement ->
                        statement.setObject(1, tenantId)
                        statement.setString(2, ruleId)
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
            check(expectedVersion == null || expectedVersion == version) {
                "compliance rule $ruleId is at version $version now, not ${expectedVersion ?: 0}"
            }
            connection
                .prepareStatement(
                    """
                    insert into octo.compliance_rule (tenant_id, rule_id, version, name, definition, actor, correlation_id)
                    values (?, ?, ?, ?, ?::jsonb, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setString(2, ruleId)
                    statement.setInt(3, version)
                    statement.setString(4, name)
                    statement.setString(5, json.writeValueAsString(definition(check)))
                    statement.setString(6, provenance.actor)
                    statement.setObject(7, provenance.correlationId)
                    statement.executeUpdate()
                }
            ComplianceRule(ruleId, version, name, check)
        }
    }

    override fun retire(
        tenantId: UUID,
        ruleId: String,
        provenance: ComplianceProvenance,
        scope: TenantScope,
    ): ComplianceRule? {
        require(scope.admits(tenantId)) { "tenant $tenantId is outside the scoped tenants" }
        return dataSource.scoped(scope) { connection ->
            lockRule(connection, tenantId, ruleId)
            val latest =
                connection
                    .prepareStatement(
                        """
                        select version, name, definition::text, active
                        from octo.compliance_rule where tenant_id = ? and rule_id = ?
                        order by version desc limit 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, tenantId)
                        statement.setString(2, ruleId)
                        statement.executeQuery().use { rows ->
                            if (!rows.next()) return@scoped null
                            Latest(
                                version = rows.getInt(1),
                                name = rows.getString(2),
                                definition = rows.getString(3),
                                active = rows.getBoolean(4),
                            )
                        }
                    }
            val check = parseCheck(json.readTree(latest.definition))
            if (!latest.active) return@scoped ComplianceRule(ruleId, latest.version, latest.name, check)
            val version = latest.version + 1
            connection
                .prepareStatement(
                    """
                    insert into octo.compliance_rule (tenant_id, rule_id, version, name, definition, active, actor, correlation_id)
                    values (?, ?, ?, ?, ?::jsonb, false, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setString(2, ruleId)
                    statement.setInt(3, version)
                    statement.setString(4, latest.name)
                    statement.setString(5, latest.definition)
                    statement.setString(6, provenance.actor)
                    statement.setObject(7, provenance.correlationId)
                    statement.executeUpdate()
                }
            ComplianceRule(ruleId, version, latest.name, check)
        }
    }

    override fun breachTask(
        tenantId: UUID,
        evaluation: Evaluation,
        scope: TenantScope,
    ): UUID? {
        if (!scope.admits(tenantId)) return null
        val sql =
            """
            select task_id from octo.compliance_evaluation
            where tenant_id = ? and rule_id = ? and subject = ? and as_of_date = ? and result = 'breach'
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, evaluation.rule.id)
                statement.setString(3, evaluation.subject)
                statement.setObject(4, evaluation.asOf)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("task_id", UUID::class.java) else null }
            }
        }
    }

    override fun record(
        tenantId: UUID,
        evaluation: Evaluation,
        taskId: UUID?,
        correlationId: UUID,
        scope: TenantScope,
        openTask: ((Connection) -> Unit)?,
    ): UUID {
        require(scope.admits(tenantId)) { "tenant $tenantId is outside the scoped tenants" }
        val sql =
            """
            insert into octo.compliance_evaluation (tenant_id, rule_id, rule_version, subject, as_of_date, result, measured, explanation,
                                                     task_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            returning id
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
            openTask?.invoke(connection)
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, evaluation.rule.id)
                statement.setInt(3, evaluation.rule.version)
                statement.setString(4, evaluation.subject)
                statement.setObject(5, evaluation.asOf)
                statement.setString(6, evaluation.result.wireValue)
                statement.setString(7, json.writeValueAsString(evaluation.measured))
                statement.setString(8, evaluation.explanation)
                statement.setObject(9, taskId)
                statement.setObject(10, correlationId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getObject("id", UUID::class.java)
                }
            }
        }
    }

    private class Latest(
        val version: Int,
        val name: String,
        val definition: String,
        val active: Boolean,
    )

    private fun lockRule(
        connection: java.sql.Connection,
        tenantId: UUID,
        ruleId: String,
    ) {
        connection
            .prepareStatement(
                "select pg_advisory_xact_lock(hashtextextended('octo.compliance_rule:' || ?::text || ':' || ?::text, 0))",
            ).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, ruleId)
                statement.executeQuery().close()
            }
    }

    private fun definition(check: ComplianceCheck): Map<String, String> =
        when (check) {
            is ComplianceCheck.ConcentrationLimit ->
                mapOf(
                    "check" to "concentration-limit",
                    "maxFraction" to check.maxFraction.toPlainString(),
                )
            is ComplianceCheck.CurrencyExposureLimit ->
                mapOf(
                    "check" to "currency-exposure-limit",
                    "currency" to check.currency.currencyCode,
                    "maxFraction" to check.maxFraction.toPlainString(),
                )
            is ComplianceCheck.CoverageFloor -> mapOf("check" to "coverage-floor", "minRatio" to check.minRatio.toPlainString())
        }

    private fun parseCheck(node: JsonNode): ComplianceCheck {
        fun decimal(field: String) = BigDecimal(node[field]?.asText() ?: error("rule definition lacks $field"))
        return when (val kind = node["check"]?.asText()) {
            "concentration-limit" -> ComplianceCheck.ConcentrationLimit(decimal("maxFraction"))
            "currency-exposure-limit" ->
                ComplianceCheck.CurrencyExposureLimit(
                    Currency.getInstance(node["currency"].asText()),
                    decimal("maxFraction"),
                )
            "coverage-floor" -> ComplianceCheck.CoverageFloor(decimal("minRatio"))
            else -> error("unknown compliance check '$kind'")
        }
    }
}
