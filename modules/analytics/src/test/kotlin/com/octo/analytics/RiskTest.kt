package com.mesta.asset.analytics

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun nav(
    date: String,
    value: String,
) = NavObservation(LocalDate.parse(date), BigDecimal(value))

private fun flow(
    date: String,
    amount: String,
) = CashFlow(LocalDate.parse(date), BigDecimal(amount))

private fun assertClose(
    expected: Double,
    actual: Double?,
    tolerance: Double = 1e-12,
) = assertEquals(expected, assertNotNull(actual), tolerance)

// Mean 2%, sample volatility exactly 2%.
private val EVEN = listOf(0.00, 0.02, 0.04)

class RiskTest {
    @Test
    fun `twr chains subperiods across a call and a distribution`() {
        val navs = listOf(nav("2021-03-31", "100"), nav("2021-06-30", "160"), nav("2021-09-30", "122"))
        val flows =
            listOf(
                flow("2021-01-15", "-100"), // already in the first NAV
                flow("2021-06-30", "-50"), // call: (160 − 50) / 100 = 1.10
                flow("2021-09-30", "30"), // distribution: (122 + 30) / 160 = 0.95
            )

        val twr = assertNotNull(timeWeightedReturn(navs, flows))
        assertEquals(0, BigDecimal("0.045").compareTo(twr), "twr was $twr")
    }

    @Test
    fun `a flow between nav observations is rejected, not moved to the period end`() {
        val navs = listOf(nav("2021-03-31", "100"), nav("2021-06-30", "160"))
        assertFailsWith<IllegalArgumentException> { timeWeightedReturn(navs, listOf(flow("2021-05-15", "-50"))) }
        assertFailsWith<IllegalArgumentException> { timeWeightedReturn(navs, listOf(flow("2021-07-01", "10"))) }
    }

    @Test
    fun `a subperiod starting from zero nav makes twr undefined`() {
        val navs = listOf(nav("2021-03-31", "0"), nav("2021-06-30", "100"))
        assertNull(timeWeightedReturn(navs, listOf(flow("2021-06-30", "-100"))))
    }

    @Test
    fun `nav observations must be ordered, non-negative and at least two`() {
        assertFailsWith<IllegalArgumentException> { timeWeightedReturn(listOf(nav("2021-03-31", "100")), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            timeWeightedReturn(listOf(nav("2021-06-30", "100"), nav("2021-03-31", "100")), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            timeWeightedReturn(listOf(nav("2021-03-31", "100"), nav("2021-06-30", "-1")), emptyList())
        }
    }

    @Test
    fun `volatility, sharpe and sortino follow methodology 3_3`() {
        assertClose(0.02, volatility(EVEN))
        assertClose(0.5, sharpe(EVEN, riskFree = 0.01)) // (2% − 1%) / 2%
        // Excess over a 1% target: −1%, 1%, 3%; downside deviation √(0.01² / 3).
        assertClose(sqrt(3.0), sortino(EVEN, target = 0.01), 1e-9)
    }

    @Test
    fun `measures without enough information are undefined, not zero`() {
        assertNull(volatility(listOf(0.05)))
        assertNull(sharpe(listOf(0.05), riskFree = 0.0))
        assertNull(sharpe(listOf(0.03, 0.03), riskFree = 0.0), "zero volatility")
        assertNull(sortino(listOf(0.02, 0.03), target = 0.01), "no downside")
        assertNull(sortino(emptyList(), target = 0.0))
        assertNull(maxDrawdown(emptyList()))
        assertNull(parametricVar(listOf(0.05), confidence = 0.99))
        assertNull(historicalExpectedShortfall(emptyList(), confidence = 0.95))
    }

    @Test
    fun `max drawdown is measured on the wealth index`() {
        // Index 1.10, 0.88, 0.924, 1.155, 1.0395: the worst fall is 1.10 → 0.88.
        assertClose(0.2, maxDrawdown(listOf(0.10, -0.20, 0.05, 0.25, -0.10)))
        assertClose(0.0, maxDrawdown(listOf(0.01, 0.02)))
    }

    @Test
    fun `normal quantile matches reference values in the centre and both tails`() {
        assertEquals(0.0, normalQuantile(0.5), 1e-12)
        assertEquals(1.959963984540054, normalQuantile(0.975), 1e-8)
        assertEquals(2.3263478740408408, normalQuantile(0.99), 1e-8)
        assertEquals(3.090232306167813, normalQuantile(0.999), 1e-8)
        assertEquals(-2.3263478740408408, normalQuantile(0.01), 1e-8)
        assertFailsWith<IllegalArgumentException> { normalQuantile(1.0) }
    }

    @Test
    fun `parametric var scales volatility by the square root of the horizon`() {
        val z = 2.3263478740408408
        assertClose(z * 0.02 - 0.02, parametricVar(EVEN, confidence = 0.99), 1e-9)
        assertClose(z * 0.02 * 2 - 0.08, parametricVar(EVEN, confidence = 0.99, horizon = 4), 1e-9)
        assertFailsWith<IllegalArgumentException> { parametricVar(EVEN, confidence = 0.99, horizon = 0) }
    }

    @Test
    fun `historical expected shortfall averages the tail`() {
        val returns = listOf(-0.05, -0.03) + List(18) { 0.01 * (it + 1) }

        // 10% of 20 returns is 2: the tail is −5% and −3%.
        assertClose(0.04, historicalExpectedShortfall(returns, confidence = 0.90))
        // 5% of 20 is exactly 1, not a float that rounds up to 2.
        assertClose(0.05, historicalExpectedShortfall(returns, confidence = 0.95))
        // q is −2%, the 2nd of 4; its tie is in the tail too, so three returns average to −8% / 3, not −3%.
        assertClose(0.08 / 3, historicalExpectedShortfall(listOf(-0.04, -0.02, -0.02, 0.03), confidence = 0.5))
    }

    @Test
    fun `portfolio volatility follows methodology 3_2`() {
        val covariance = listOf(listOf(0.04, 0.006), listOf(0.006, 0.09))
        // 0.36·0.04 + 2·0.6·0.4·0.006 + 0.16·0.09 = 0.03168
        assertClose(sqrt(0.03168), portfolioVolatility(listOf(0.6, 0.4), covariance))

        val notCovariance = listOf(listOf(0.01, 0.05), listOf(0.05, 0.01))
        assertFailsWith<IllegalArgumentException> { portfolioVolatility(listOf(1.0, -1.0), notCovariance) }
        assertFailsWith<IllegalArgumentException> { portfolioVolatility(listOf(1.0), covariance) }
    }

    @Test
    fun `risk report carries the valuation basis and the formula version`() {
        val report = riskReport(EVEN, ValuationBasis.APPRAISAL, riskFree = 0.01, target = 0.01, confidence = 0.99)

        assertEquals(ValuationBasis.APPRAISAL, report.basis)
        assertEquals(3, report.periods)
        assertClose(0.02, report.mean)
        assertClose(0.5, report.sharpe)
        assertClose(0.0, report.maxDrawdown)
        assertEquals(parametricVar(EVEN, 0.99), report.valueAtRisk)
        assertClose(0.0, report.expectedShortfall) // ⌈3 · 1%⌉ = 1: the worst return, 0%
        assertEquals(RISK_METHODOLOGY, report.methodology)
    }

    @Test
    fun `non-finite inputs are rejected rather than turned into a result`() {
        val broken = listOf(-0.05, Double.NaN, 0.01, 0.02)

        // #37: this returned 0.02, because the NaN counted towards the tail size but never entered the tail.
        assertFailsWith<IllegalArgumentException> { historicalExpectedShortfall(broken, confidence = 0.5) }
        // #37: this returned NaN.
        assertFailsWith<IllegalArgumentException> { maxDrawdown(listOf(0.1, Double.NaN, -0.2)) }
        assertFailsWith<IllegalArgumentException> { volatility(listOf(0.01, Double.POSITIVE_INFINITY)) }
        assertFailsWith<IllegalArgumentException> { parametricVar(broken, confidence = 0.99) }
        assertFailsWith<IllegalArgumentException> { sharpe(EVEN, riskFree = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { sortino(EVEN, target = Double.NaN) }
        assertFailsWith<IllegalArgumentException> {
            riskReport(
                broken,
                ValuationBasis.MARKET,
                riskFree = 0.0,
                target = 0.0,
                confidence = 0.95,
            )
        }
        val covariance = listOf(listOf(0.04, 0.0), listOf(0.0, 0.09))
        assertFailsWith<IllegalArgumentException> { portfolioVolatility(listOf(0.5, Double.NaN), covariance) }
    }
}
