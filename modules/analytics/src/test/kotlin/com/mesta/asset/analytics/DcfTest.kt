package com.mesta.asset.analytics

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun d(value: String) = BigDecimal(value)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
}

// A 25% WACC keeps every discount factor exact in decimal: 1.25, 1.5625, 1.953125.
private val BASE =
    DcfInputs(
        valuationDate = LocalDate.parse("2025-12-31"),
        currency = Currency.getInstance("USD"),
        freeCashFlows = listOf(d("125"), d("156.25"), d("195.3125")),
        terminalCashFlow = d("200"),
        wacc = d("0.25"),
        terminalGrowth = d("0.05"),
        netDebt = d("100"),
        preferredClaims = d("12"),
        nonOperatingAssets = d("50"),
    )

class DcfTest {
    @Test
    fun `dcf follows methodology 5_1 component by component`() {
        val valuation = dcf(BASE)

        assertDecimal("300", valuation.presentValueOfCashFlows) // 100 + 100 + 100
        assertDecimal("1000", valuation.terminalValue) // 200 / (25% − 5%)
        assertDecimal("512", valuation.presentValueOfTerminalValue) // 1000 / 1.953125
        assertDecimal("812", valuation.enterpriseValue)
        assertDecimal("750", valuation.equityValue) // 812 − 100 − 12 + 50
        assertEquals(BASE, valuation.inputs, "the valuation keeps its inputs for lineage")
        assertEquals(VALUATION_METHODOLOGY, valuation.methodology)
    }

    @Test
    fun `wacc follows methodology 5_2 from a built-up cost of equity`() {
        val equityCost =
            costOfEquity(
                riskFree = d("0.04"),
                leveredBeta = d("1.2"),
                marketReturn = d("0.09"),
                sizePremium = d("0.01"),
                countryPremium = d("0.005"),
                specificPremium = d("0.005"),
            )

        assertDecimal("0.12", equityCost) // 4% + 1.2 × 5% + 2% of premia
        assertDecimal("0.10", costOfEquity(riskFree = d("0.04"), leveredBeta = d("1.2"), marketReturn = d("0.09"))) // plain CAPM
        // 60% · 12% + 40% · 6% · (1 − 25%) = 7.2% + 1.8%
        assertDecimal(
            "0.09",
            wacc(equity = d("600"), debt = d("400"), costOfEquity = equityCost, costOfDebt = d("0.06"), taxRate = d("0.25")),
        )
    }

    @Test
    fun `the sensitivity grid reproduces the point estimate and leaves undefined cells null`() {
        // Asymmetric axes pin the orientation: rows are WACCs, columns are growth rates.
        val grid = dcfSensitivity(BASE, waccs = listOf(d("0.20"), d("0.25")), growths = listOf(d("0.03"), d("0.05"), d("0.25")))

        assertEquals(2, grid.equityValues.size)
        assertEquals(listOf(3, 3), grid.equityValues.map { it.size })
        assertDecimal("750", grid.equityValues[1][1]) // 25% WACC, 5% growth: the point estimate
        assertNull(grid.equityValues[1][2], "WACC equal to g has no terminal value")
        // Python Decimal at 40 digits: 325.70167824… of cash flows + 1333.33… / 1.728 of terminal value − 62.
        val cheaperCapital = assertNotNull(grid.equityValues[0][1])
        assertTrue((cheaperCapital - d("1035.306616512345679")).abs() < d("1e-9"), "was $cheaperCapital")
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { dcf(BASE.copy(terminalGrowth = d("0.25"))) }
        assertFailsWith<IllegalArgumentException> { dcf(BASE.copy(wacc = d("-1"), terminalGrowth = d("-2"))) }
        assertFailsWith<IllegalArgumentException> { BASE.copy(freeCashFlows = emptyList()) }
        assertFailsWith<IllegalArgumentException> { BASE.copy(preferredClaims = d("-1")) } // would add to equity
        assertFailsWith<IllegalArgumentException> { dcfSensitivity(BASE, emptyList(), listOf(d("0.05"))) }
        assertFailsWith<IllegalArgumentException> { DcfSensitivity(listOf(d("0.1")), listOf(d("0.05")), emptyList()) }
        assertFailsWith<IllegalArgumentException> { wacc(d("-1"), d("1"), d("0.1"), d("0.05"), d("0.2")) }
        assertFailsWith<IllegalArgumentException> { wacc(d("1"), d("-1"), d("0.1"), d("0.05"), d("0.2")) }
        assertFailsWith<IllegalArgumentException> { wacc(d("0"), d("0"), d("0.1"), d("0.05"), d("0.2")) }
        assertFailsWith<IllegalArgumentException> { wacc(d("1"), d("1"), d("0.1"), d("0.05"), d("1.5")) }
    }
}
