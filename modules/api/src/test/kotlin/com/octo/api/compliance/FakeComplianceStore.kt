package com.octo.api.compliance

import com.octo.api.FAKE_CONNECTION
import com.octo.persistence.TenantScope
import com.octo.recon.compliance.ComplianceCheck
import com.octo.recon.compliance.ComplianceRule
import com.octo.recon.compliance.Evaluation
import com.octo.recon.compliance.Result
import com.octo.recon.compliance.persistence.ComplianceProvenance
import com.octo.recon.compliance.persistence.ComplianceStore
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** In-memory `ComplianceStore` with V14's one-breach-per-key rule, for the runner and endpoint tests. */
class FakeComplianceStore : ComplianceStore {
    /** (rule version row, still active) — a retire is another row, like the real store. */
    val rules = mutableMapOf<UUID, MutableList<Pair<ComplianceRule, Boolean>>>()
    val recorded = mutableListOf<Triple<Evaluation, UUID?, UUID>>()

    override fun activeRules(
        tenantId: UUID,
        scope: TenantScope,
    ) = rules[tenantId]
        .orEmpty()
        .groupBy { it.first.id }
        .values
        .mapNotNull { versions -> versions.maxBy { it.first.version }.takeIf { it.second }?.first }

    override fun defineRule(
        tenantId: UUID,
        ruleId: String,
        name: String,
        check: ComplianceCheck,
        expectedVersion: Int?,
        provenance: ComplianceProvenance,
        scope: TenantScope,
    ): ComplianceRule {
        val versions = rules.getOrPut(tenantId) { mutableListOf() }
        val version = versions.filter { it.first.id == ruleId }.maxOfOrNull { it.first.version }?.plus(1) ?: 1
        check(expectedVersion == null || expectedVersion == version) {
            "compliance rule $ruleId is at version $version now, not ${expectedVersion ?: 0}"
        }
        return ComplianceRule(ruleId, version, name, check).also { versions += it to true }
    }

    override fun retire(
        tenantId: UUID,
        ruleId: String,
        provenance: ComplianceProvenance,
        scope: TenantScope,
    ): ComplianceRule? {
        val versions = rules[tenantId] ?: return null
        val latest = versions.filter { it.first.id == ruleId }.maxByOrNull { it.first.version } ?: return null
        if (!latest.second) return latest.first
        return latest.first.copy(version = latest.first.version + 1).also { versions += it to false }
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
        openTask: ((Connection) -> Unit)?,
    ): UUID {
        // A refused insert rolls the transaction back before the task commits, so the conflict is raised first.
        if (evaluation.result == Result.BREACH &&
            breachTask(tenantId, evaluation, scope) != null
        ) {
            throw SQLException("compliance_breach_once", "23505")
        }
        openTask?.invoke(FAKE_CONNECTION)
        return UUID.randomUUID().also { recorded += Triple(evaluation, taskId, it) }
    }

    private fun Evaluation.sameKey(other: Evaluation) = rule.id == other.rule.id && subject == other.subject && asOf == other.asOf
}
