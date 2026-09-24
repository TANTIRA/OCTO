package com.mesta.asset.analytics

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private fun d(value: String) = BigDecimal(value)

private fun on(date: String) = LocalDate.parse(date)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, d(expected).compareTo(actual), "expected $expected but was $actual")
}

// 100 in at entry, a 20 dividend recap after two years, exit after five at 8 × 50 of EBITDA with 150 of net debt.
private val DEAL =
    LboInputs(
        currency = Currency.getInstance("USD"),
        entryDate = on("2020-01-01"),
        initialSponsorEquity = d("100"),
        exitDate = on("2025-01-01"),
        exitMetric = d("50"),
        exitMultiple = d("8"),
        exitNetDebt = d("150"),
        interimDistributions = listOf(CashFlow(on("2022-01-01"), d("20"))),
    )

class LboTest {
    @Test
    fun `sponsor returns follow methodology 5_4`() {
        val returns = lboReturns(DEAL)

        assertDecimal("400", returns.exitEnterpriseValue) // 50 × 8
        assertDecimal("250", returns.exitEquity) // 400 − 150
        assertDecimal("250", returns.exitProceeds)
        assertDecimal("2.7", returns.moic) // (250 + 20) / 100
        // Python bisection on the same actual/365 flows: −100, +20 in 2022, +250 in 2025.
        assertEquals(0.235058585259, assertNotNull(returns.irr), 1e-6)
        assertEquals(DEAL, returns.inputs)
        assertEquals(LBO_METHODOLOGY, returns.methodology)
    }

    @Test
    fun `exit equity below zero is reported as written but proceeds floor at zero`() {
        val returns = lboReturns(DEAL.copy(exitMultiple = d("2"))) // EV 100 against 150 of net debt

        assertDecimal("-50", returns.exitEquity)
        assertDecimal("0", returns.exitProceeds)
        assertDecimal("0.2", returns.moic) // only the 20 recap came back
        // Python: −100 then +20 two years later, the exit returning nothing.
        assertEquals(-0.552293819798, assertNotNull(returns.irr), 1e-6)
    }

    @Test
    fun `invalid deals are rejected`() {
        assertFailsWith<IllegalArgumentException> { DEAL.copy(initialSponsorEquity = d("0")) }
        assertFailsWith<IllegalArgumentException> { DEAL.copy(exitDate = on("2020-01-01")) }
        assertFailsWith<IllegalArgumentException> { DEAL.copy(exitMultiple = d("-1")) }
        assertFailsWith<IllegalArgumentException> { DEAL.copy(interimDistributions = listOf(CashFlow(on("2022-01-01"), d("-5")))) }
        assertFailsWith<IllegalArgumentException> { DEAL.copy(interimDistributions = listOf(CashFlow(on("2019-12-31"), d("5")))) }
        assertFailsWith<IllegalArgumentException> { DEAL.copy(interimDistributions = listOf(CashFlow(on("2025-01-02"), d("5")))) }
    }
}
