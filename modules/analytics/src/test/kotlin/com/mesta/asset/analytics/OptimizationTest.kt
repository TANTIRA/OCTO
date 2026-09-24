package com.mesta.asset.analytics

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val IDENTITY = listOf(listOf(1.0, 0.0), listOf(0.0, 1.0))
private val NO_BOUNDS = listOf(0.0, 0.0) to listOf(1.0, 1.0)

private fun assertWeights(
    expected: List<Double>,
    actual: Allocation,
) {
    assertTrue(actual.converged, "solver did not converge in ${actual.iterations} iterations")
    expected.zip(actual.weights).forEachIndexed { i, (e, a) -> assertEquals(e, a, 1e-7, "weight $i") }
    assertEquals(1.0, actual.weights.sum(), 1e-9)
}

class OptimizationTest {
    @Test
    fun `minimum variance splits uncorrelated assets by inverse variance`() {
        val covariance = listOf(listOf(1.0, 0.0), listOf(0.0, 4.0))
        val allocation = meanVariance(listOf(0.1, 0.2), covariance, 0.0, NO_BOUNDS.first, NO_BOUNDS.second)

        assertWeights(listOf(0.8, 0.2), allocation) // w_i ∝ 1/σ_i²
        assertEquals(0.12, allocation.expectedReturn, 1e-7) // 0.8 × 0.1 + 0.2 × 0.2
        assertEquals(sqrt(0.64 + 0.16), allocation.volatility, 1e-7)
        assertEquals(OPTIMIZATION_METHODOLOGY, allocation.methodology)
    }

    @Test
    fun `an upper bound binds`() {
        val covariance = listOf(listOf(1.0, 0.0), listOf(0.0, 4.0))
        val allocation = meanVariance(listOf(0.1, 0.2), covariance, 0.0, listOf(0.0, 0.0), listOf(0.6, 1.0))

        assertWeights(listOf(0.6, 0.4), allocation)
    }

    @Test
    fun `interior solution matches the KKT conditions`() {
        // Σ = I, λ = 1: w = λμ + ν·1 with ν = (1 − Σλμ) / n = 0.35.
        val allocation = meanVariance(listOf(0.1, 0.2), IDENTITY, 1.0, NO_BOUNDS.first, NO_BOUNDS.second)

        assertWeights(listOf(0.45, 0.55), allocation)
        assertEquals(1.0, allocation.lambda)
    }

    @Test
    fun `a large lambda fills the best asset up to its cap`() {
        val allocation = meanVariance(listOf(0.1, 0.2, 0.15), identity(3), 1_000.0, listOf(0.0, 0.0, 0.0), listOf(1.0, 0.7, 1.0))

        assertWeights(listOf(0.0, 0.7, 0.3), allocation)
    }

    @Test
    fun `lower bounds force a minimum holding`() {
        val allocation = meanVariance(listOf(0.1, 0.2), IDENTITY, 1_000.0, listOf(0.25, 0.0), listOf(1.0, 1.0))

        assertWeights(listOf(0.25, 0.75), allocation)
    }

    @Test
    fun `correlated assets are hedged`() {
        // Perfectly correlated with different variances: variance is (w₁ + 2w₂)², minimised at the lower bound of asset 2.
        val covariance = listOf(listOf(1.0, 2.0), listOf(2.0, 4.0))
        val allocation = meanVariance(listOf(0.0, 0.0), covariance, 0.0, listOf(0.0, -0.5), listOf(2.0, 1.0))

        assertWeights(listOf(1.5, -0.5), allocation)
        assertEquals(0.5, allocation.volatility, 1e-6) // |1.5 − 1.0|
    }

    @Test
    fun `an unconverged solve is reported, not hidden`() {
        val allocation =
            meanVariance(
                listOf(0.1, 0.2),
                listOf(listOf(1.0, 0.0), listOf(0.0, 4.0)),
                0.0,
                NO_BOUNDS.first,
                NO_BOUNDS.second,
                maxIterations = 1,
            )

        assertFalse(allocation.converged)
        assertEquals(1, allocation.iterations)
        assertEquals(1.0, allocation.weights.sum(), 1e-9)
    }

    @Test
    fun `invalid inputs are rejected`() {
        val (lower, upper) = NO_BOUNDS
        assertFailsWith<IllegalArgumentException> { meanVariance(listOf(0.1), IDENTITY, 0.0, lower, upper) }
        assertFailsWith<IllegalArgumentException> { meanVariance(listOf(0.1, 0.2), IDENTITY, 0.0, listOf(0.0), upper) }
        assertFailsWith<IllegalArgumentException> { meanVariance(listOf(Double.NaN, 0.2), IDENTITY, 0.0, lower, upper) }
        assertFailsWith<IllegalArgumentException> { meanVariance(listOf(0.1, 0.2), IDENTITY, -1.0, lower, upper) }
        assertFailsWith<IllegalArgumentException> { meanVariance(listOf(0.1, 0.2), IDENTITY, 0.0, listOf(0.5, 0.0), listOf(0.4, 1.0)) }
        assertFailsWith<IllegalArgumentException> { meanVariance(listOf(0.1, 0.2), IDENTITY, 0.0, listOf(0.6, 0.6), upper) }
        assertFailsWith<IllegalArgumentException> { meanVariance(listOf(0.1, 0.2), IDENTITY, 0.0, lower, listOf(0.4, 0.4)) }
        assertFailsWith<IllegalArgumentException> { meanVariance(emptyList(), emptyList(), 0.0, emptyList(), emptyList()) }
    }

    private fun identity(n: Int) = List(n) { i -> List(n) { j -> if (i == j) 1.0 else 0.0 } }
}
