package com.mesta.asset.analytics

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun d(value: String) = BigDecimal(value)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
}

class ForecastTest {
    @Test
    fun `growth and margins follow methodology 6_1`() {
        assertDecimal("0.1", growth(d("100"), d("110")))
        assertEquals(0.1, assertNotNull(cagr(d("100"), d("121"), 2.0)), 1e-12)
        assertEquals(-1.0, assertNotNull(cagr(d("100"), d("0"), 3.0)), 1e-12)
        assertDecimal("0.25", ebitdaMargin(d("25"), d("100")))
        // 100 × (1 − 0.25) + 20 − 30 − 10
        assertDecimal("55", freeCashFlow(d("100"), d("0.25"), d("20"), d("30"), d("10")))
    }

    @Test
    fun `growth from a zero or negative base is undefined, not a sign-inverted number`() {
        assertNull(growth(d("0"), d("5")))
        assertNull(growth(d("-10"), d("5"))) // the formula would say −150%
        assertNull(cagr(d("-10"), d("5"), 2.0))
        assertNull(cagr(d("10"), d("-5"), 2.0))
        assertNull(ebitdaMargin(d("5"), d("0")))
    }

    @Test
    fun `weighted forecast follows methodology 6_2`() {
        assertEquals(17.5, weightedForecast(mapOf("a" to 10.0, "b" to 20.0), mapOf("a" to 0.25, "b" to 0.75)), 1e-12)
        assertFailsWith<IllegalArgumentException> { weightedForecast(mapOf("a" to 10.0), mapOf("a" to 0.9)) }
        assertFailsWith<IllegalArgumentException> { weightedForecast(mapOf("a" to 10.0), mapOf("b" to 1.0)) }
        assertFailsWith<IllegalArgumentException> { weightedForecast(mapOf("a" to Double.NaN), mapOf("a" to 1.0)) }
    }

    @Test
    fun `mae and rmse follow methodology 6_2`() {
        val errors = forecastErrors(listOf(1.0, 2.0, 3.0), listOf(1.0, 3.0, 5.0)) // errors 0, 1, 2

        assertEquals(3, errors.count)
        assertEquals(1.0, errors.mae, 1e-12)
        assertEquals(kotlin.math.sqrt(5.0 / 3.0), errors.rmse, 1e-12)
        assertEquals(FORECAST_METHODOLOGY, errors.methodology)
    }

    @Test
    fun `walk-forward scores a naive forecast out of sample`() {
        // Origins 2, 3, 4 predict 2, 3, 4 against actuals 3, 4, 5.
        val errors = assertNotNull(walkForward(listOf(1.0, 2.0, 3.0, 4.0, 5.0), minTrain = 2) { it.last() })

        assertEquals(3, errors.count)
        assertEquals(1.0, errors.mae, 1e-12)
    }

    @Test
    fun `walk-forward never shows the forecaster its own target`() {
        // The value at index i is i. A forecaster that peeked at the target would see one more point and miss by one.
        val series = (0..9).map { it.toDouble() }

        assertEquals(0.0, assertNotNull(walkForward(series, minTrain = 3) { it.size.toDouble() }).mae, 0.0)
        // Two steps ahead from origin t is index t + 1, which the history of size t has not seen either.
        assertEquals(0.0, assertNotNull(walkForward(series, minTrain = 3, horizon = 2) { it.size + 1.0 }).mae, 0.0)
        assertNull(walkForward(listOf(1.0, 2.0), minTrain = 2) { it.last() })
    }

    @Test
    fun `bayesian update follows methodology 6_3 and keeps what it must store`() {
        val pE = evidenceProbability(prior = 0.3, likelihoodIfTrue = 0.8, likelihoodIfFalse = 0.2) // 0.24 + 0.14
        val at = Instant.parse("2026-06-30T12:00:00Z")
        val update = bayesianUpdate("revenue beats plan", 0.3, 0.8, pE, at, LocalDate.parse("2026-06-30"))

        assertEquals(0.38, pE, 1e-12)
        assertEquals(0.24 / 0.38, update.posterior, 1e-12)
        assertEquals(update.posterior, update.effective)
        assertEquals(LocalDate.parse("2026-06-30"), update.dataVintage)

        val overridden = update.overriddenBy(0.5, "Q3 pipeline already signed")
        assertEquals(0.5, overridden.effective)
        assertEquals(update.posterior, overridden.posterior)
    }

    @Test
    fun `inconsistent probabilities and unexplained overrides are rejected`() {
        val at = Instant.parse("2026-06-30T12:00:00Z")
        val vintage = LocalDate.parse("2026-06-30")
        val update = bayesianUpdate("h", 0.3, 0.8, 0.38, at, vintage)

        assertFailsWith<IllegalArgumentException> { bayesianUpdate("h", 0.3, 0.8, 0.1, at, vintage) } // P(E) < 0.24
        assertFailsWith<IllegalArgumentException> { bayesianUpdate("h", 0.3, 0.8, 0.0, at, vintage) }
        assertFailsWith<IllegalArgumentException> { bayesianUpdate("h", 1.3, 0.8, 0.38, at, vintage) }
        assertFailsWith<IllegalArgumentException> { update.overriddenBy(0.5, " ") }
        assertFailsWith<IllegalArgumentException> { update.copy(posterior = 0.9) }
        assertFailsWith<IllegalArgumentException> { freeCashFlow(d("1"), d("1.5"), d("0"), d("0"), d("0")) }
        assertFailsWith<IllegalArgumentException> { cagr(d("1"), d("2"), 0.0) }
    }
}
