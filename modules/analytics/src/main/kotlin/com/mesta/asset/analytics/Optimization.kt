package com.mesta.asset.analytics

import kotlin.math.abs
import kotlin.math.max

/** Formula set every result below is computed under (quantitative-methodology.md §7.1, §10.5). */
const val OPTIMIZATION_METHODOLOGY = "quantitative-methodology §7.1 v1"

/**
 * A §7.1 allocation. [weights] follow the input order and sum to one. [expectedReturn] is μᵀw and
 * [volatility] is √(wᵀΣw) (§3.2), both per period of the inputs: nothing is annualized. [converged] is
 * false when the solver hit [iterations] before its tolerance; the weights are then feasible but not
 * optimal, and a consumer must say so rather than present them as the optimum.
 */
data class Allocation(
    val weights: List<Double>,
    val expectedReturn: Double,
    val volatility: Double,
    val lambda: Double,
    val iterations: Int,
    val converged: Boolean,
    val methodology: String = OPTIMIZATION_METHODOLOGY,
)

/**
 * min ½wᵀΣw − λμᵀw subject to 1ᵀw = 1 and lower ≤ w ≤ upper (methodology §7.1).
 *
 * [lambda] is §7.1's λ: 0 is the minimum-variance portfolio, larger values buy expected return with
 * variance. There is no default. The bounds are per asset, so a concentration limit is an upper bound;
 * the group constraints §7.1 lists (sector, geography, vintage, manager, currency, liquidity) are not
 * modelled here. Only the symmetric part of [covariance] affects the result.
 */
fun meanVariance(
    expectedReturns: List<Double>,
    covariance: List<List<Double>>,
    lambda: Double,
    lower: List<Double>,
    upper: List<Double>,
    tolerance: Double = 1e-10,
    maxIterations: Int = 100_000,
): Allocation {
    val n = expectedReturns.size
    require(n > 0) { "at least one asset is needed" }
    require(covariance.size == n && covariance.all { it.size == n }) { "covariance must be $n×$n" }
    require(lower.size == n && upper.size == n) { "bounds must have one entry per asset" }
    requireFinite("expected returns", expectedReturns)
    requireFinite("covariance", covariance.flatten())
    requireFinite("bounds", lower + upper)
    require(lambda.isFinite() && lambda >= 0) { "λ must be finite and not negative; 0 is the minimum-variance portfolio" }
    require(tolerance > 0 && maxIterations > 0) { "tolerance and maxIterations must be positive" }
    for (i in 0 until n) require(lower[i] <= upper[i]) { "asset $i: lower bound ${lower[i]} exceeds upper bound ${upper[i]}" }
    require(lower.sum() <= 1.0 && upper.sum() >= 1.0) { "bounds admit no fully invested portfolio: 1ᵀw = 1 needs Σlower ≤ 1 ≤ Σupper" }

    val sigma = Array(n) { i -> DoubleArray(n) { j -> (covariance[i][j] + covariance[j][i]) / 2 } }
    // Gershgorin bound on the largest eigenvalue: 1/L is a safe gradient step for a convex quadratic.
    val lipschitz = sigma.maxOf { row -> row.sumOf { abs(it) } }
    val step = if (lipschitz > 0) 1 / lipschitz else 1.0

    // ponytail: projected gradient, O(κ·log(1/tol)) iterations; switch to an active-set QP if wide, ill-conditioned covariances are slow.
    var w = project(DoubleArray(n) { 1.0 / n }, lower, upper)
    var iterations = 0
    var converged = false
    while (iterations < maxIterations) {
        iterations++
        val gradient = DoubleArray(n) { i -> (0 until n).sumOf { j -> sigma[i][j] * w[j] } - lambda * expectedReturns[i] }
        val next = project(DoubleArray(n) { i -> w[i] - step * gradient[i] }, lower, upper)
        val change = (0 until n).maxOf { abs(next[it] - w[it]) }
        w = next
        if (change < tolerance) {
            converged = true
            break
        }
    }

    val weights = w.toList()
    return Allocation(
        weights = weights,
        expectedReturn = (0 until n).sumOf { expectedReturns[it] * weights[it] },
        volatility = portfolioVolatility(weights, covariance),
        lambda = lambda,
        iterations = iterations,
        converged = converged,
    )
}

/**
 * Euclidean projection onto {1ᵀw = 1, lower ≤ w ≤ upper}: w_i = clamp(v_i − ν, lower_i, upper_i) with ν
 * found by bisection, since Σ clamp(v_i − ν) is continuous and non-increasing in ν.
 */
private fun project(
    v: DoubleArray,
    lower: List<Double>,
    upper: List<Double>,
): DoubleArray {
    fun clamped(nu: Double) = DoubleArray(v.size) { i -> (v[i] - nu).coerceIn(lower[i], upper[i]) }
    val spread = (v.indices).maxOf { max(abs(v[it] - lower[it]), abs(v[it] - upper[it])) }
    var lo = -spread - 1
    var hi = spread + 1
    repeat(200) {
        val mid = (lo + hi) / 2
        if (clamped(mid).sum() > 1.0) lo = mid else hi = mid
    }
    return clamped((lo + hi) / 2)
}
