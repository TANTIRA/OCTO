package com.mesta.asset.api.compliance

import com.mesta.asset.recon.compliance.ComplianceRule
import com.mesta.asset.recon.compliance.Evaluation
import com.mesta.asset.recon.compliance.Result
import com.mesta.asset.recon.compliance.persistence.ComplianceProvenance
import com.mesta.asset.recon.compliance.persistence.ComplianceStore
import java.sql.SQLException
import java.util.UUID

/** In-memory `ComplianceStore` with V14's one-breach-per-key rule, for the runner and endpoint tests. */
class FakeComplianceStore : ComplianceStore {
    val rules = mutableMapOf<UUID, MutableList<ComplianceRule>>()
    val recorded = mutableListOf<Triple<Evaluation, UUID?, UUID>>()

    override fun activeRules(tenantId: UUID) =
        rules[tenantId]
            .orEmpty()
            .groupBy {
                it.id
            }.values
            .map { versions -> versions.maxBy { it.version } }

    override fun defineRule(
        tenantId: UUID,
        rule: ComplianceRule,
        provenance: ComplianceProvenance,
    ) {
        val versions = rules.getOrPut(tenantId) { mutableListOf() }
        if (versions.any { it.id == rule.id && it.version == rule.version }) throw SQLException("duplicate version", "23505")
        versions += rule
    }

    override fun breachTask(
        tenantId: UUID,
        evaluation: Evaluation,
    ) = recorded.firstOrNull { (e, _, _) -> e.result == Result.BREACH && e.sameKey(evaluation) }?.second

    override fun record(
        tenantId: UUID,
        evaluation: Evaluation,
        taskId: UUID?,
        correlationId: UUID,
    ): UUID {
        if (evaluation.result == Result.BREACH &&
            breachTask(tenantId, evaluation) != null
        ) {
            throw SQLException("compliance_breach_once", "23505")
        }
        return UUID.randomUUID().also { recorded += Triple(evaluation, taskId, it) }
    }

    private fun Evaluation.sameKey(other: Evaluation) = rule.id == other.rule.id && subject == other.subject && asOf == other.asOf
}
