package com.mesta.asset.api.compliance

import com.mesta.asset.analytics.CoverageReport
import com.mesta.asset.lookthrough.ExposureReport
import com.mesta.asset.recon.compliance.ComplianceCheck
import com.mesta.asset.recon.compliance.ComplianceInputs
import com.mesta.asset.recon.compliance.ComplianceRule
import com.mesta.asset.recon.compliance.Result
import com.mesta.asset.recon.compliance.persistence.ComplianceProvenance
import com.mesta.asset.workflow.Task
import com.mesta.asset.workflow.TaskKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

class ComplianceRunnerTest {
    private val store = FakeComplianceStore()
    private val opened = mutableListOf<Task>()
    private val runner = ComplianceRunner(store) { task, _ -> opened += task }
    private val tenantId = UUID.randomUUID()
    private val usd = Currency.getInstance("USD")
    private val asOf = LocalDate.parse("2026-06-30")
    private val provenance = ComplianceProvenance("officer", UUID.randomUUID())

    private fun inputs(ratio: String?) =
        ComplianceInputs(
            "fund-1",
            asOf,
            exposure = ExposureReport("fund-1", usd, mapOf("a" to BigDecimal("60"), "b" to BigDecimal("40"))),
            coverage = CoverageReport(asOf, asOf.plusYears(1), usd, "base", null, null, null, null, ratio?.let { BigDecimal(it) }),
        )

    @Test
    fun `a breach opens one review task, a pass and a not-evaluable record without one, and a re-run reuses the task`() {
        store.defineRule(
            tenantId,
            ComplianceRule("conc", 1, "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.5"))),
            provenance,
        )
        store.defineRule(tenantId, ComplianceRule("cov", 1, "Coverage", ComplianceCheck.CoverageFloor(BigDecimal("1.2"))), provenance)
        store.defineRule(
            tenantId,
            ComplianceRule("eur", 1, "EUR", ComplianceCheck.CurrencyExposureLimit(Currency.getInstance("EUR"), BigDecimal("0.4"))),
            provenance,
        )

        val first = runner.run(tenantId, inputs(ratio = "1.5"), "runner", UUID.randomUUID())
        assertThat(first.map { it.evaluation.rule.id to it.evaluation.result })
            .containsExactly("conc" to Result.BREACH, "cov" to Result.PASS, "eur" to Result.NOT_EVALUABLE) // 60/100 > 0.5
        val breach = first.single { it.evaluation.result == Result.BREACH }
        assertThat(breach.taskId).isNotNull()
        assertThat(breach.recorded).isTrue()
        assertThat(opened.single()).satisfies({
            assertThat(it.kind).isEqualTo(TaskKind.REVIEW)
            assertThat(it.subjectType).isEqualTo("compliance-breach")
            assertThat(it.subjectId).isEqualTo("conc/fund-1/2026-06-30")
            assertThat(it.requestedBy).isEqualTo("runner")
        })
        assertThat(store.recorded).hasSize(3)
        assertThat(store.recorded.map { it.second }.filterNotNull()).containsExactly(breach.taskId)

        val again = runner.run(tenantId, inputs(ratio = "1.5"), "runner", UUID.randomUUID())
        val same = again.single { it.evaluation.result == Result.BREACH }
        assertThat(same.taskId).isEqualTo(breach.taskId)
        assertThat(same.recorded).isFalse()
        assertThat(opened).hasSize(1)
        assertThat(store.recorded).hasSize(5) // the pass and the not-evaluable record again; the breach does not
    }

    @Test
    fun `a tenant with no rules records nothing and a losing race adopts the winner's task`() {
        assertThat(runner.run(tenantId, inputs("1.5"), "runner", UUID.randomUUID())).isEmpty()
        assertThat(store.recorded).isEmpty()

        store.defineRule(tenantId, ComplianceRule("cov", 1, "Coverage", ComplianceCheck.CoverageFloor(BigDecimal("1.2"))), provenance)
        // Another runner records the breach between our breachTask lookup and our record: simulate by pre-recording after the check.
        val racing =
            ComplianceRunner(
                object : com.mesta.asset.recon.compliance.persistence.ComplianceStore by store {
                    override fun breachTask(
                        tenantId: UUID,
                        evaluation: com.mesta.asset.recon.compliance.Evaluation,
                    ) = store.breachTask(tenantId, evaluation).also {
                        if (it ==
                            null
                        ) {
                            store.record(tenantId, evaluation, WINNER, UUID.randomUUID())
                        }
                    }
                },
            ) { task, _ -> opened += task }
        val outcome = racing.run(tenantId, inputs("1.0"), "runner", UUID.randomUUID()).single()
        assertThat(outcome.evaluation.result).isEqualTo(Result.BREACH)
        assertThat(outcome.taskId).isEqualTo(WINNER)
        assertThat(outcome.recorded).isFalse()
    }

    private companion object {
        val WINNER: UUID = UUID.randomUUID()
    }
}
