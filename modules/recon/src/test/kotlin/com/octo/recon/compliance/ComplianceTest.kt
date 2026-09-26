package com.mesta.asset.recon.compliance

import com.mesta.asset.analytics.CoverageReport
import com.mesta.asset.lookthrough.ExposureReport
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val USD = Currency.getInstance("USD")
private val EUR = Currency.getInstance("EUR")
private val AS_OF = LocalDate.parse("2026-06-30")

private val concentration =
    ComplianceRule("conc-25", 1, "No holding above 25% of gross", ComplianceCheck.ConcentrationLimit(BigDecimal("0.25")))
private val euroCap = ComplianceRule("eur-40", 2, "EUR at most 40%", ComplianceCheck.CurrencyExposureLimit(EUR, BigDecimal("0.4")))
private val floor = ComplianceRule("cov-1.2", 1, "Coverage at least 1.2", ComplianceCheck.CoverageFloor(BigDecimal("1.2")))

private fun exposure(vararg assets: Pair<String, String>) =
    ExposureReport(
        "fund-1",
        USD,
        assets.associate {
            it.first to
                BigDecimal(it.second)
        },
    )

private fun coverage(ratio: String?) =
    CoverageReport(AS_OF, AS_OF.plusYears(1), USD, "base", null, null, null, null, ratio?.let { BigDecimal(it) })

private fun inputs(
    exposure: ExposureReport? = null,
    currencies: Map<Currency, BigDecimal>? = null,
    coverage: CoverageReport? = null,
) = ComplianceInputs("fund-1", AS_OF, exposure, currencies, coverage)

class ComplianceTest {
    @Test
    fun `concentration compares the largest holding with gross exposure`() {
        val breach = evaluate(listOf(concentration), inputs(exposure("a" to "20", "b" to "30", "c" to "50"))).single()
        assertEquals(Result.BREACH, breach.result) // 50 / 100 = 0.5 > 0.25
        assertEquals("0.5", breach.measured["fraction"])
        val zero = evaluate(listOf(concentration), inputs(exposure("a" to "0"))).single()
        assertEquals(Result.NOT_EVALUABLE, zero.result)
    }

    @Test
    fun `concentration breaches on the largest absolute holding, shorts included`() {
        val breach = evaluate(listOf(concentration), inputs(exposure("a" to "10", "b" to "-30", "c" to "10"))).single()
        assertEquals(Result.BREACH, breach.result) // gross = 50, |−30| / 50 = 0.6
        assertEquals("0.6", breach.measured["fraction"])
        assertEquals("0.25", breach.measured["limit"])
        assertEquals(COMPLIANCE_RULESET, breach.ruleset)
        val ok = evaluate(listOf(concentration), inputs(exposure("a" to "10", "b" to "10", "c" to "10", "d" to "10", "e" to "10"))).single()
        assertEquals(Result.PASS, ok.result)
    }

    @Test
    fun `currency limit treats an absent currency as zero and a zero total as not evaluable`() {
        val breach = evaluate(listOf(euroCap), inputs(currencies = mapOf(USD to BigDecimal("50"), EUR to BigDecimal("-50")))).single()
        assertEquals(Result.BREACH, breach.result) // 50 / 100 = 0.5 > 0.4
        val absent = evaluate(listOf(euroCap), inputs(currencies = mapOf(USD to BigDecimal("50")))).single()
        assertEquals(Result.PASS, absent.result)
        assertEquals("0", absent.measured["fraction"])
        val empty = evaluate(listOf(euroCap), inputs(currencies = mapOf(USD to BigDecimal.ZERO))).single()
        assertEquals(Result.NOT_EVALUABLE, empty.result)
    }

    @Test
    fun `coverage floor breaches below the floor and an undefined ratio is not evaluable, never a pass`() {
        assertEquals(Result.BREACH, evaluate(listOf(floor), inputs(coverage = coverage("1.1"))).single().result)
        assertEquals(Result.PASS, evaluate(listOf(floor), inputs(coverage = coverage("1.2"))).single().result)
        val undefined = evaluate(listOf(floor), inputs(coverage = coverage(null))).single()
        assertEquals(Result.NOT_EVALUABLE, undefined.result)
        assertEquals("undefined", undefined.measured["ratio"])
    }

    @Test
    fun `missing inputs make every rule that needs them not evaluable, and each evaluation carries its rule version`() {
        val all = evaluate(listOf(concentration, euroCap, floor), inputs())
        assertEquals(listOf(Result.NOT_EVALUABLE, Result.NOT_EVALUABLE, Result.NOT_EVALUABLE), all.map { it.result })
        assertEquals(listOf(1, 2, 1), all.map { it.rule.version })
        assertEquals("fund-1", all.first().subject)
    }

    @Test
    fun `rules are validated`() {
        assertFailsWith<IllegalArgumentException> { ComplianceRule(" ", 1, "x", ComplianceCheck.CoverageFloor(BigDecimal.ONE)) }
        assertFailsWith<IllegalArgumentException> { ComplianceRule("r", 0, "x", ComplianceCheck.CoverageFloor(BigDecimal.ONE)) }
        assertFailsWith<IllegalArgumentException> { ComplianceRule("r", 1, "x", ComplianceCheck.ConcentrationLimit(BigDecimal("-0.1"))) }
    }
}
