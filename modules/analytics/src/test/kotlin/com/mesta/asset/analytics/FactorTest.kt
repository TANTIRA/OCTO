package com.mesta.asset.analytics

import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val MARKET = listOf(0.012, -0.021, 0.034, 0.003, 0.018, -0.011, 0.025, -0.006, 0.009, -0.015)
private val SIZE = listOf(0.004, 0.002, -0.003, 0.005, -0.001, 0.003, -0.004, 0.001, 0.002, -0.002)
private val NOISE = listOf(0.0011, -0.0023, 0.0016, 0.0004, -0.0012, 0.0021, -0.0007, 0.0002, -0.0015, 0.0003)

// α = 0.2%, β = 1.3 plus noise: the regression the closed forms below are checked against.
private val NOISY = MARKET.indices.map { 0.002 + 1.3 * MARKET[it] + NOISE[it] }

private fun month(i: Int) = LocalDate.of(2024, 1, 31).plusMonths(i.toLong())

private fun observations(
    returns: List<Double>,
    vararg factors: Pair<String, List<Double>>,
) = returns.indices.map { t -> FactorObservation(month(t), returns[t], factors.associate { (name, values) -> name to values[t] }) }

private fun capm(
    returns: List<Double>,
    standardErrors: StandardErrors,
) = factorModel(observations(returns, "MKT-RF" to MARKET), "monthly", "synthetic-market", standardErrors)

/** Closed-form one-factor OLS: the slope's variance is aᵀSa / Sxx² with a = (−x̄, 1), so u_t = (x_t − x̄)·e_t. */
private object ClosedForm {
    val n = MARKET.size
    val xBar = MARKET.average()
    val sxx = MARKET.sumOf { (it - xBar) * (it - xBar) }
    val beta = MARKET.indices.sumOf { (MARKET[it] - xBar) * NOISY[it] } / sxx
    val alpha = NOISY.average() - beta * xBar
    val e = MARKET.indices.map { NOISY[it] - alpha - beta * MARKET[it] }
    val s2 = e.sumOf { it * it } / (n - 2)
    val u = MARKET.indices.map { (MARKET[it] - xBar) * e[it] }

    fun neweyWestSlopeVariance(lags: Int) =
        (u.sumOf { it * it } + 2 * (1..lags).sumOf { l -> (1 - l / (lags + 1.0)) * (l until n).sumOf { u[it] * u[it - l] } }) / (sxx * sxx)
}

class FactorTest {
    @Test
    fun `an exact linear relation is recovered with no residual error`() {
        val model = capm(MARKET.map { 0.002 + 1.3 * it }, StandardErrors.Classical)

        assertEquals(0.002, model.alpha.estimate, 1e-12)
        assertEquals(1.3, model.betas.getValue("MKT-RF").estimate, 1e-12)
        assertEquals(1.0, model.rSquared!!, 1e-12)
        assertEquals(0.0, model.betas.getValue("MKT-RF").standardError, 1e-9)
    }

    @Test
    fun `classical standard errors match the one-factor closed form`() {
        val model = capm(NOISY, StandardErrors.Classical)
        val beta = model.betas.getValue("MKT-RF")

        assertEquals(ClosedForm.alpha, model.alpha.estimate, 1e-12)
        assertEquals(ClosedForm.beta, beta.estimate, 1e-12)
        assertEquals(sqrt(ClosedForm.s2 / ClosedForm.sxx), beta.standardError, 1e-12)
        val alphaSe = sqrt(ClosedForm.s2 * (1.0 / ClosedForm.n + ClosedForm.xBar * ClosedForm.xBar / ClosedForm.sxx))
        assertEquals(alphaSe, model.alpha.standardError, 1e-12)
    }

    @Test
    fun `hc1 matches White's closed form with the n over n minus k correction`() {
        val beta = capm(NOISY, StandardErrors.Hc1).betas.getValue("MKT-RF")
        val n = ClosedForm.n.toDouble()
        val expected = sqrt(n / (n - 2) * ClosedForm.neweyWestSlopeVariance(lags = 0))

        assertEquals(expected, beta.standardError, 1e-12)
    }

    @Test
    fun `newey-west adds bartlett-weighted autocovariances and reduces to hc0 at zero lags`() {
        val twoLags = capm(NOISY, StandardErrors.NeweyWest(2)).betas.getValue("MKT-RF")
        assertEquals(sqrt(ClosedForm.neweyWestSlopeVariance(lags = 2)), twoLags.standardError, 1e-12)

        val zeroLags = capm(NOISY, StandardErrors.NeweyWest(0)).betas.getValue("MKT-RF")
        assertEquals(sqrt(ClosedForm.neweyWestSlopeVariance(lags = 0)), zeroLags.standardError, 1e-12)
    }

    @Test
    fun `a multifactor relation is recovered per named factor`() {
        val returns = MARKET.indices.map { 0.001 + 0.8 * MARKET[it] - 0.5 * SIZE[it] }
        val model = factorModel(observations(returns, "MKT-RF" to MARKET, "SMB" to SIZE), "monthly", "synthetic-market", StandardErrors.Hc1)

        assertEquals(0.001, model.alpha.estimate, 1e-12)
        assertEquals(0.8, model.betas.getValue("MKT-RF").estimate, 1e-12)
        assertEquals(-0.5, model.betas.getValue("SMB").estimate, 1e-12)
    }

    @Test
    fun `two-factor newey-west matches an independent numpy reference`() {
        val returns = MARKET.indices.map { 0.001 + 0.8 * MARKET[it] - 0.5 * SIZE[it] + NOISE[it] }
        val model =
            factorModel(
                observations(returns, "MKT-RF" to MARKET, "SMB" to SIZE),
                "monthly",
                "synthetic-market",
                StandardErrors.NeweyWest(2),
            )

        // numpy 2.0.2: inv(XᵀX) · S · inv(XᵀX) with Bartlett weights, no small-sample correction.
        assertEquals(0.00083623453706068492, model.alpha.estimate, 1e-12)
        assertEquals(0.00030572344561328682, model.alpha.standardError, 1e-12)
        assertEquals(0.81971202326525616, model.betas.getValue("MKT-RF").estimate, 1e-12)
        assertEquals(0.03684007690383391, model.betas.getValue("MKT-RF").standardError, 1e-12)
        assertEquals(-0.40121749819130481, model.betas.getValue("SMB").estimate, 1e-12)
        assertEquals(0.11975630227638175, model.betas.getValue("SMB").standardError, 1e-12)
        assertEquals(0.99215241683239819, model.rSquared!!, 1e-12)
    }

    @Test
    fun `collinear factors are rejected rather than solved`() {
        val doubled = MARKET.map { 2 * it }
        assertFailsWith<IllegalArgumentException> {
            factorModel(
                observations(NOISY, "MKT-RF" to MARKET, "LEVERED" to doubled),
                "monthly",
                "synthetic-market",
                StandardErrors.Classical,
            )
        }
    }

    @Test
    fun `malformed inputs are rejected`() {
        val valid = observations(NOISY, "MKT-RF" to MARKET)
        val declared = { obs: List<FactorObservation> -> factorModel(obs, "monthly", "synthetic-market", StandardErrors.Classical) }

        assertFailsWith<IllegalArgumentException> { declared(valid.reversed()) }
        assertFailsWith<IllegalArgumentException> { declared(valid.take(2)) }
        assertFailsWith<IllegalArgumentException> { declared(valid.dropLast(1) + valid.last().copy(factors = mapOf("SMB" to 0.0))) }
        assertFailsWith<IllegalArgumentException> { declared(valid.map { it.copy(factors = emptyMap()) }) }
        assertFailsWith<IllegalArgumentException> { StandardErrors.NeweyWest(-1) }
        assertContains(assertFailsWith<IllegalArgumentException> { declared(emptyList()) }.message.orEmpty(), "observation")
    }

    @Test
    fun `non-finite values are rejected with a message about the value`() {
        val valid = observations(NOISY, "MKT-RF" to MARKET)
        val declared = { obs: List<FactorObservation> -> factorModel(obs, "monthly", "synthetic-market", StandardErrors.Hc1) }
        val badReturn = valid.mapIndexed { t, o -> if (t == 3) o.copy(excessReturn = Double.NaN) else o }
        val badFactor = valid.mapIndexed { t, o -> if (t == 3) o.copy(factors = mapOf("MKT-RF" to Double.POSITIVE_INFINITY)) else o }

        // #37: a NaN return gave a model of NaNs, and a bad factor value was reported as collinearity.
        assertContains(assertFailsWith<IllegalArgumentException> { declared(badReturn) }.message.orEmpty(), "finite")
        assertContains(assertFailsWith<IllegalArgumentException> { declared(badFactor) }.message.orEmpty(), "finite")
    }

    @Test
    fun `newey-west lags must be fewer than the observations`() {
        // #37: 50 lags on 10 observations were silently capped at 9.
        assertFailsWith<IllegalArgumentException> { capm(NOISY, StandardErrors.NeweyWest(10)) }
        assertEquals(10, capm(NOISY, StandardErrors.NeweyWest(9)).observations)
    }

    @Test
    fun `the estimate carries its declarations and window`() {
        val model = capm(NOISY, StandardErrors.NeweyWest(2))

        assertEquals(month(0), model.windowStart)
        assertEquals(month(9), model.windowEnd)
        assertEquals(10, model.observations)
        assertEquals("monthly", model.frequency)
        assertEquals("synthetic-market", model.benchmark)
        assertEquals(StandardErrors.NeweyWest(2), model.standardErrors)
        assertEquals(FACTOR_METHODOLOGY, model.methodology)
        assertNull(capm(MARKET.map { 0.01 }, StandardErrors.Classical).rSquared, "constant returns leave nothing to explain")
    }
}
