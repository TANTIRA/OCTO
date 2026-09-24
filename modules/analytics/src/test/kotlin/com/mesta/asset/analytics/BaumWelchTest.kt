package com.mesta.asset.analytics

import java.time.LocalDate
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val START = LocalDate.parse("2026-01-01")

private fun hmm(
    means: List<Double>,
    sigmas: List<Double>,
    transition: List<List<Double>>,
    initial: List<Double> = listOf(0.5, 0.5),
) = HiddenMarkovModel(listOf(GaussianRegime("bull", means[0], sigmas[0]), GaussianRegime("bear", means[1], sigmas[1])), transition, initial)

/** A deterministic sample path from [truth]. */
private fun simulate(
    truth: HiddenMarkovModel,
    periods: Int,
    seed: Long,
): List<RegimeObservation> {
    val random = Random(seed)
    var state = if (random.nextDouble() < truth.initial[0]) 0 else 1
    return List(periods) { t ->
        if (t > 0) state = if (random.nextDouble() < truth.transition[state][0]) 0 else 1
        val regime = truth.regimes[state]
        RegimeObservation(START.plusDays(t.toLong()), regime.mean + regime.standardDeviation * random.nextGaussian())
    }
}

class BaumWelchTest {
    private val truth = hmm(listOf(2.0, -2.0), listOf(0.5, 0.5), listOf(listOf(0.95, 0.05), listOf(0.1, 0.9)))
    private val sample = simulate(truth, periods = 600, seed = 7)

    @Test
    fun `recovers the generating parameters from a rough start`() {
        val start = hmm(listOf(1.0, -1.0), listOf(2.0, 2.0), listOf(listOf(0.5, 0.5), listOf(0.5, 0.5)))
        val estimate = baumWelch(start, sample, minimumSigma = 1e-3)

        assertTrue(estimate.converged, "did not converge in ${estimate.iterations} iterations")
        val (bull, bear) = estimate.model.regimes
        assertEquals(2.0, bull.mean, 0.15)
        assertEquals(-2.0, bear.mean, 0.15)
        assertEquals(0.5, bull.standardDeviation, 0.1)
        assertEquals(0.95, estimate.model.transition[0][0], 0.05)
        assertEquals(0.9, estimate.model.transition[1][1], 0.08)
        assertEquals(REGIME_METHODOLOGY, estimate.methodology)
    }

    @Test
    fun `each iteration never lowers the likelihood`() {
        val start = hmm(listOf(0.5, -0.5), listOf(3.0, 3.0), listOf(listOf(0.6, 0.4), listOf(0.4, 0.6)))
        var previous = regimeFilter(start, sample).logLikelihood
        var model = start
        repeat(5) {
            val step = baumWelch(model, sample, minimumSigma = 1e-3, maxIterations = 1)
            assertTrue(step.logLikelihood >= previous - 1e-9, "likelihood fell from $previous to ${step.logLikelihood}")
            previous = step.logLikelihood
            model = step.model
        }
        assertEquals(previous, regimeFilter(model, sample).logLikelihood, 1e-9)
    }

    @Test
    fun `sigma is floored and an unconverged run says so`() {
        // Two identical observations: the M-step variance is zero, so σ lands on the floor.
        val estimate =
            baumWelch(truth, List(2) { RegimeObservation(START.plusDays(it.toLong()), 2.0) }, minimumSigma = 0.25, maxIterations = 1)

        assertEquals(0.25, estimate.model.regimes[0].standardDeviation)
        assertEquals(1, estimate.iterations)
        assertTrue(!estimate.converged)
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { baumWelch(truth, sample.take(1), minimumSigma = 0.1) }
        assertFailsWith<IllegalArgumentException> {
            baumWelch(truth, sample.take(3) + RegimeObservation(START.plusDays(3), null), minimumSigma = 0.1)
        }
        assertFailsWith<IllegalArgumentException> { baumWelch(truth, sample, minimumSigma = 0.0) }
        assertFailsWith<IllegalArgumentException> { baumWelch(truth, sample, minimumSigma = 0.1, maxIterations = 0) }
    }
}
