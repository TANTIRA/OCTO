package com.octo.api.compliance

import com.octo.recon.compliance.ComplianceRule
import com.octo.recon.compliance.Evaluation
import com.octo.recon.compliance.Result
import com.octo.recon.compliance.persistence.ComplianceProvenance
import com.octo.recon.compliance.persistence.ComplianceStore
import java.sql.SQLException
import java.util.UUID
import com.octo.recon.persistence.TenantScope

/** In-memory `ComplianceStore` with V14's one-breach-per-key rule, for the runner and endpoint tests. */
class FakeComplianceStore : ComplianceStore {
    val rules = mutableMapOf<UUID, MutableList<ComplianceRule>>()
    val recorded = mutableListOf<Triple<Evaluation, UUID?, UUID>>()

    override fun activeRules(
        tenantId: UUID,
        scope: TenantScope,
    ) =
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
        scope: TenantScope,
    ) {
        val versions = rules.getOrPut(tenantId) { mutableListOf() }
        if (versions.any { it.id == rule.id && it.version == rule.version }) throw SQLException("duplicate version", "23505")
        versions += rule
    }

    override fun breachTask(
        tenantId: UUID,
        evaluation: Evaluation,
        scope: TenantScope,
    ) = recorded.firstOrNull { (e, _, _) -> e.result == Result.BREACH && e.sameKey(evaluation) }?.second

    override fun record(
        tenantId: UUID,
        evaluation: Evaluation,
        taskId: UUID?,
        correlationId: UUID,
        scope: TenantScope,
    ): UUID {
        if (evaluation.result == Result.BREACH &&
            breachTask(tenantId, evaluation, scope) != null
        ) {
            throw SQLException("compliance_breach_once", "23505")
        }
        return UUID.randomUUID().also { recorded += Triple(evaluation, taskId, it) }
    }

    private fun Evaluation.sameKey(other: Evaluation) = rule.id == other.rule.id && subject == other.subject && asOf == other.asOf
}
