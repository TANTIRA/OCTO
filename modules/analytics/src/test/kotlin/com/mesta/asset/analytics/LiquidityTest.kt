package com.mesta.asset.analytics

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val USD = Currency.getInstance("USD")
private val EUR = Currency.getInstance("EUR")

private fun usd(value: String) = Amount(USD, BigDecimal(value))

private fun eur(value: String) = Amount(EUR, BigDecimal(value))

private fun position(
    liquid: List<Amount> = emptyList(),
    inflows: List<Amount> = emptyList(),
    unfunded: List<Amount> = emptyList(),
    outflows: List<Amount> = emptyList(),
) = LiquidityPosition(LocalDate.parse("2026-06-30"), LocalDate.parse("2027-06-30"), liquid, inflows, unfunded, outflows)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
}

class LiquidityTest {
    private val base = position(listOf(usd("50")), listOf(usd("30")), listOf(usd("60")), listOf(usd("20")))

    @Test
    fun `coverage follows methodology 7_3`() {
        val report = coverage(base, USD, emptyMap())

        assertDecimal("1", report.ratio) // (50 + 30) / (60 + 20)
        assertDecimal("50", report.liquidAssets)
        assertDecimal("20", report.forecastOutflows)
        assertEquals("base", report.scenario)
        assertEquals(LIQUIDITY_METHODOLOGY, report.methodology)
    }

    @Test
    fun `amounts convert into the reporting currency`() {
        val mixed = position(liquid = listOf(eur("100"), usd("10")), unfunded = listOf(usd("100")))
        val report = coverage(mixed, USD, mapOf(EUR to BigDecimal("1.1")))

        assertDecimal("120", report.liquidAssets) // 100 × 1.1 + 10
        assertDecimal("1.2", report.ratio)
    }

    @Test
    fun `each stress lever moves its own term`() {
        val stress =
            LiquidityStress(
                name = "slow exits, fast calls",
                liquidAssets = BigDecimal("0.8"),
                forecastInflows = BigDecimal("0.5"),
                forecastOutflows = BigDecimal("2"),
            )
        val report = coverage(base, USD, emptyMap(), stress)

        assertDecimal("0.55", report.ratio) // (40 + 15) / (60 + 40)
        assertEquals("slow exits, fast calls", report.scenario)
    }

    @Test
    fun `an fx shock scales that currency's rate`() {
        val shocked = LiquidityStress("EUR -10%", fxShocks = mapOf(EUR to BigDecimal("0.9")))
        val euroAssets = position(liquid = listOf(eur("100")), unfunded = listOf(usd("99")))
        val report = coverage(euroAssets, USD, mapOf(EUR to BigDecimal("1.1")), shocked)

        assertDecimal("99", report.liquidAssets) // 100 × 1.1 × 0.9
        assertDecimal("1", report.ratio)
    }

    @Test
    fun `a currency without a rate makes its term and the ratio undefined, not zero`() {
        val report = coverage(position(liquid = listOf(eur("100")), unfunded = listOf(usd("50"))), USD, emptyMap())

        assertNull(report.liquidAssets)
        assertDecimal("50", report.unfundedCommitments)
        assertNull(report.ratio)
    }

    @Test
    fun `nothing owed means the ratio is undefined`() {
        assertNull(coverage(position(liquid = listOf(usd("50"))), USD, emptyMap()).ratio)
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { usd("-1") }
        assertFailsWith<IllegalArgumentException> { LiquidityStress("bad", liquidAssets = BigDecimal("-0.1")) }
        assertFailsWith<IllegalArgumentException> { LiquidityStress("bad", fxShocks = mapOf(EUR to BigDecimal.ZERO)) }
        assertFailsWith<IllegalArgumentException> { base.copy(horizonEnd = LocalDate.parse("2026-01-01")) }
        assertFailsWith<IllegalArgumentException> { coverage(base, USD, mapOf(EUR to BigDecimal.ZERO)) }

        val shockedReporting = LiquidityStress("bad", fxShocks = mapOf(USD to BigDecimal("0.9")))
        assertFailsWith<IllegalArgumentException> { coverage(base, USD, emptyMap(), shockedReporting) }
    }
}
