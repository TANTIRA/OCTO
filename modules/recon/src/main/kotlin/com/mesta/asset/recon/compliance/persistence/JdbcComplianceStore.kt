package com.mesta.asset.recon.compliance.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.recon.compliance.ComplianceCheck
import com.mesta.asset.recon.compliance.ComplianceRule
import com.mesta.asset.recon.compliance.Evaluation
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import javax.sql.DataSource

/** Where a rule or evaluation row came from, for the provenance columns V14 requires. */
data class ComplianceProvenance(
    val actor: String,
    val correlationId: UUID,
)

/** What the runner and endpoints read and write. */
interface ComplianceStore {
    /** The latest active version of every rule of the tenant. */
    fun activeRules(tenantId: UUID): List<ComplianceRule>

    fun defineRule(
        tenantId: UUID,
        rule: ComplianceRule,
        provenance: ComplianceProvenance,
    )

    /** The task already opened for this breach on this date, if V14's `compliance_breach_once` already holds a row. */
    fun breachTask(
        tenantId: UUID,
        evaluation: Evaluation,
    ): UUID?

    /** Records one evaluation; a breach carries the task it opened. Returns the row id. */
    fun record(
        tenantId: UUID,
        evaluation: Evaluation,
        taskId: UUID?,
        correlationId: UUID,
    ): UUID
}

/**
 * JDBC access to `mesta.compliance_rule` and `mesta.compliance_evaluation` (V14). A rule's `definition` is the
 * [ComplianceCheck] as json: `{"check": "concentration-limit", "maxFraction": "0.25"}`,
 * `{"check": "currency-exposure-limit", "currency": "EUR", "maxFraction": "0.4"}`, `{"check": "coverage-floor", "minRatio": "1.2"}`.
 */
class JdbcComplianceStore(
    private val dataSource: DataSource,
) : ComplianceStore {
    private val json = ObjectMapper()

    override fun activeRules(tenantId: UUID): List<ComplianceRule> {
        val sql =
            """
            select distinct on (rule_id) rule_id, version, name, definition::text
            from mesta.compliance_rule where tenant_id = ? and active
            order by rule_id, version desc
            """.trimIndent()
        return dataSource.connection.use { connection ->
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

    override fun defineRule(
        tenantId: UUID,
        rule: ComplianceRule,
        provenance: ComplianceProvenance,
    ) {
        val sql =
            """
            insert into mesta.compliance_rule (tenant_id, rule_id, version, name, definition, actor, correlation_id)
            values (?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, rule.id)
                statement.setInt(3, rule.version)
                statement.setString(4, rule.name)
                statement.setString(5, json.writeValueAsString(definition(rule.check)))
                statement.setString(6, provenance.actor)
                statement.setObject(7, provenance.correlationId)
                statement.executeUpdate()
            }
        }
    }

    override fun breachTask(
        tenantId: UUID,
        evaluation: Evaluation,
    ): UUID? {
        val sql =
            """
            select task_id from mesta.compliance_evaluation
            where tenant_id = ? and rule_id = ? and subject = ? and as_of_date = ? and result = 'breach'
            """.trimIndent()
        return dataSource.connection.use { connection ->
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
    ): UUID {
        val sql =
            """
            insert into mesta.compliance_evaluation (tenant_id, rule_id, rule_version, subject, as_of_date, result, measured, explanation,
                                                     task_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            returning id
            """.trimIndent()
        return dataSource.connection.use { connection ->
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
