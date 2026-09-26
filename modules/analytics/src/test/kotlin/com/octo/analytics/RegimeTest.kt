package com.mesta.asset.analytics

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val START = LocalDate.parse("2026-01-01")
private val STICKY = listOf(listOf(0.9, 0.1), listOf(0.2, 0.8))

private fun model(
    bull: GaussianRegime = GaussianRegime("bull", 1.0, 1.0),
    bear: GaussianRegime = GaussianRegime("bear", -1.0, 1.0),
    transition: List<List<Double>> = STICKY,
) = HiddenMarkovModel(listOf(bull, bear), transition, listOf(0.5, 0.5))

private fun series(vararg y: Double?) = y.mapIndexed { i, v -> RegimeObservation(START.plusDays(i.toLong()), v) }

private fun assertDistribution(
    expected: List<Double>,
    actual: List<Double>,
) = expected.zip(actual).forEachIndexed { i, (e, a) -> assertEquals(e, a, 1e-9, "entry $i") }

class RegimeTest {
    @Test
    fun `identical emissions leave the transition matrix in charge`() {
        val same = model(GaussianRegime("a", 0.0, 1.0), GaussianRegime("b", 0.0, 1.0))
        val result = regimeFilter(same, series(0.3, -0.7))

        assertDistribution(listOf(0.5, 0.5), result.estimates[0].filtered)
        assertDistribution(listOf(0.55, 0.45), result.estimates[0].predictedNext) // πA
        assertDistribution(listOf(0.55, 0.45), result.estimates[1].filtered)
        assertEquals(REGIME_METHODOLOGY, result.methodology)
    }

    @Test
    fun `one step follows the forward recursion and its likelihood`() {
        // y = 1: bull density φ(0), bear density φ(2); α ∝ (0.5 φ(0), 0.5 φ(2)).
        val phi0 = 1 / sqrt(2 * PI)
        val phi2 = exp(-2.0) / sqrt(2 * PI)
        val result = regimeFilter(model(), series(1.0))

        assertDistribution(listOf(phi0 / (phi0 + phi2), phi2 / (phi0 + phi2)), result.estimates[0].filtered)
        assertEquals(ln(0.5 * phi0 + 0.5 * phi2), result.estimates[0].logLikelihood!!, 1e-12)
        assertEquals(result.estimates[0].logLikelihood!!, result.logLikelihood, 1e-12)
    }

    @Test
    fun `a missing observation moves the regime only through A`() {
        val result = regimeFilter(model(), series(1.0, null))

        assertNull(result.estimates[1].logLikelihood)
        assertDistribution(result.estimates[0].predictedNext, result.estimates[1].filtered)
    }

    @Test
    fun `extreme values do not underflow`() {
        val result = regimeFilter(model(), series(1_000.0, -1_000.0))

        assertDistribution(listOf(1.0, 0.0), result.estimates[0].filtered)
        assertDistribution(listOf(0.0, 1.0), result.estimates[1].filtered)
        assert(result.logLikelihood.isFinite())
    }

    @Test
    fun `smoothing equals filtering when emissions carry no information`() {
        val same = model(GaussianRegime("a", 0.0, 1.0), GaussianRegime("b", 0.0, 1.0))
        val observations = series(0.1, 0.2, 0.3)
        val smoothed = regimeSmoother(same, observations)
        val filtered = regimeFilter(same, observations).estimates.map { it.filtered }

        smoothed.zip(filtered).forEach { (s, f) -> assertDistribution(f, s) }
    }

    @Test
    fun `smoothing uses the future`() {
        // A bear reading between two strong bull readings: filtering leans bear at t = 1, smoothing pulls it back.
        val observations = series(3.0, -0.5, 3.0)
        val filtered = regimeFilter(model(), observations).estimates[1].filtered
        val smoothed = regimeSmoother(model(), observations)

        assert(smoothed[1][0] > filtered[0]) { "smoothed bull ${smoothed[1][0]} should exceed filtered ${filtered[0]}" }
        smoothed.forEach { assertEquals(1.0, it.sum(), 1e-12) }
    }

    @Test
    fun `viterbi matches an exhaustive search`() {
        val observations = series(2.0, 0.4, -0.2, 0.3, -2.5, -1.0, 1.5)
        val m = model()

        fun logJoint(path: List<Int>) =
            ln(m.initial[path[0]]) +
                path.indices.sumOf { t -> m.regimes[path[t]].logDensity(observations[t].value!!) } +
                path.zipWithNext().sumOf { (i, j) -> ln(m.transition[i][j]) }
        val all = (0 until (1 shl observations.size)).map { bits -> observations.indices.map { (bits shr it) and 1 } }
        val best = all.maxBy { logJoint(it) }

        assertEquals(best, viterbi(m, observations))
    }

    @Test
    fun `forecast, allocation, durations and hysteresis follow 9_1 and 9_2`() {
        val m = model()
        assertEquals(10.0, m.expectedDurations()[0]!!, 1e-12)
        assertEquals(5.0, m.expectedDurations()[1]!!, 1e-12)
        assertNull(model(transition = listOf(listOf(1.0, 0.0), listOf(0.5, 0.5))).expectedDurations()[0])
        assertEquals(0.2, m.forecast(listOf(0.6, 0.4)), 1e-12) // 0.6 × 1 + 0.4 × (−1)
        assertDistribution(listOf(0.7, 0.3), probabilityWeightedAllocation(listOf(0.5, 0.5), listOf(listOf(1.0, 0.0), listOf(0.4, 0.6))))
        assertEquals(listOf(false, true, true, false, true), hysteresis(listOf(0.5, 0.75, 0.65, 0.55, 0.7), enter = 0.7, exit = 0.6))
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { GaussianRegime("x", 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { model(transition = listOf(listOf(0.9, 0.2), listOf(0.2, 0.8))) }
        assertFailsWith<IllegalArgumentException> { model(transition = listOf(listOf(1.0), listOf(1.0))) }
        assertFailsWith<IllegalArgumentException> {
            HiddenMarkovModel(listOf(GaussianRegime("a", 0.0, 1.0), GaussianRegime("a", 1.0, 1.0)), STICKY, listOf(0.5, 0.5))
        }
        assertFailsWith<IllegalArgumentException> { regimeFilter(model(), series(1.0, 2.0).reversed()) }
        assertFailsWith<IllegalArgumentException> { regimeFilter(model(), series(Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { hysteresis(listOf(0.5), enter = 0.6, exit = 0.6) }
        assertFailsWith<IllegalArgumentException> { probabilityWeightedAllocation(listOf(1.0), listOf(listOf(1.0), listOf(0.0))) }
    }
}
