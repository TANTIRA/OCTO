package com.mesta.asset.analytics

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/** Formula set every estimate below is computed under (quantitative-methodology.md §4.1, §10.5). */
const val FACTOR_METHODOLOGY = "quantitative-methodology §4.1 v1"

/** The standard-error method §4.1 requires every estimate to declare. There is deliberately no default. */
sealed interface StandardErrors {
    /** s²(XᵀX)⁻¹: homoskedastic, serially uncorrelated errors. */
    data object Classical : StandardErrors

    /** White's heteroskedasticity-consistent sandwich with the n/(n − k) correction. */
    data object Hc1 : StandardErrors

    /** Heteroskedasticity- and autocorrelation-consistent: Bartlett weights up to [lags], no small-sample correction. */
    data class NeweyWest(
        val lags: Int,
    ) : StandardErrors {
        init {
            require(lags >= 0) { "lags must not be negative" }
        }
    }
}

/** One period: the asset's return in excess of the risk-free rate, and each named factor's value. */
data class FactorObservation(
    val date: LocalDate,
    val excessReturn: Double,
    val factors: Map<String, Double>,
)

data class Coefficient(
    val estimate: Double,
    val standardError: Double,
)

/**
 * §4.1 estimate of r_t = α + Σ_k β_k·f_{k,t} + ε_t. The window is the first and last observation date;
 * [frequency], [benchmark] and [standardErrors] are the caller's declarations, carried with the numbers.
 * α is per period of the observations: nothing is annualized. [rSquared] is null when the returns do not
 * vary, because there is nothing to explain.
 */
data class FactorModel(
    val alpha: Coefficient,
    val betas: Map<String, Coefficient>,
    val rSquared: Double?,
    val observations: Int,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val frequency: String,
    val benchmark: String,
    val standardErrors: StandardErrors,
    val methodology: String = FACTOR_METHODOLOGY,
)

/**
 * Ordinary least squares of excess returns on the factors, with an intercept (methodology §4.1). CAPM is the
 * one-factor case with the market's excess return. Collinear factors have no unique solution and are rejected.
 * Observations must be equally spaced at the declared [frequency]: Newey–West counts lags in observations,
 * so a missing period would shift every autocovariance after it.
 */
fun factorModel(
    observations: List<FactorObservation>,
    frequency: String,
    benchmark: String,
    standardErrors: StandardErrors,
): FactorModel {
    require(observations.isNotEmpty()) { "at least one observation is required" }
    require(observations.zipWithNext().all { (a, b) -> a.date.isBefore(b.date) }) { "observations must have strictly increasing dates" }
    requireFinite("excess returns", observations.map { it.excessReturn })
    requireFinite("factor values", observations.flatMap { it.factors.values })
    val names =
        observations
            .firstOrNull()
            ?.factors
            ?.keys
            ?.sorted()
            .orEmpty()
    require(names.isNotEmpty()) { "at least one factor is required" }
    require(observations.all { it.factors.keys == names.toSet() }) { "every observation must carry the same factors" }
    val n = observations.size
    val k = names.size + 1
    require(n > k) { "$n observations leave no residual degrees of freedom for $k coefficients" }
    if (standardErrors is StandardErrors.NeweyWest) {
        require(standardErrors.lags < n) { "Newey–West lags (${standardErrors.lags}) must be fewer than the $n observations" }
    }

    val x = observations.map { o -> DoubleArray(k) { j -> if (j == 0) 1.0 else o.factors.getValue(names[j - 1]) } }
    val y = observations.map { it.excessReturn }
    val xtxInverse = invert(Array(k) { i -> DoubleArray(k) { j -> x.sumOf { it[i] * it[j] } } })
    val xty = DoubleArray(k) { j -> x.indices.sumOf { t -> x[t][j] * y[t] } }
    val b = DoubleArray(k) { i -> (0 until k).sumOf { j -> xtxInverse[i][j] * xty[j] } }
    val residuals = DoubleArray(n) { t -> y[t] - (0 until k).sumOf { j -> x[t][j] * b[j] } }

    val covariance =
        when (standardErrors) {
            StandardErrors.Classical -> xtxInverse.scaled(residuals.sumOf { it * it } / (n - k))
            StandardErrors.Hc1 -> sandwich(xtxInverse, x, residuals, lags = 0).scaled(n.toDouble() / (n - k))
            is StandardErrors.NeweyWest -> sandwich(xtxInverse, x, residuals, standardErrors.lags)
        }

    fun coefficient(j: Int) = Coefficient(b[j], sqrt(covariance[j][j].coerceAtLeast(0.0)))

    val mean = y.average()
    return FactorModel(
        alpha = coefficient(0),
        betas = names.withIndex().associate { (i, name) -> name to coefficient(i + 1) },
        rSquared = if (y.all { it == y[0] }) null else 1 - residuals.sumOf { it * it } / y.sumOf { (it - mean) * (it - mean) },
        observations = n,
        windowStart = observations.first().date,
        windowEnd = observations.last().date,
        frequency = frequency,
        benchmark = benchmark,
        standardErrors = standardErrors,
    )
}

/**
 * (XᵀX)⁻¹ S (XᵀX)⁻¹ with S = Σ_t e_t² x_t x_tᵀ + Σ_{l=1..L} (1 − l/(L + 1)) Σ_t e_t e_{t−l} (x_t x_{t−l}ᵀ + x_{t−l} x_tᵀ).
 * With L = 0 this is White's HC0.
 */
private fun sandwich(
    xtxInverse: Array<DoubleArray>,
    x: List<DoubleArray>,
    residuals: DoubleArray,
    lags: Int,
): Array<DoubleArray> {
    val k = xtxInverse.size
    val meat = Array(k) { DoubleArray(k) }
    for (lag in 0..lags) {
        val weight = 1 - lag / (lags + 1.0)
        for (t in lag until x.size) {
            val e = weight * residuals[t] * residuals[t - lag]
            for (i in 0 until k) {
                for (j in 0 until k) {
                    val cross = x[t][i] * x[t - lag][j]
                    meat[i][j] += e * if (lag == 0) cross else cross + x[t - lag][i] * x[t][j]
                }
            }
        }
    }
    return xtxInverse.times(meat).times(xtxInverse)
}

/** Gauss–Jordan inverse with partial pivoting. */
private fun invert(matrix: Array<DoubleArray>): Array<DoubleArray> {
    val n = matrix.size
    val a = Array(n) { matrix[it].copyOf() }
    val inverse = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
    val scale = matrix.maxOf { row -> row.maxOf { abs(it) } }
    for (col in 0 until n) {
        val pivot = (col until n).maxBy { abs(a[it][col]) }
        // ponytail: fixed relative tolerance on the normal equations; move to a rank-revealing QR if near-collinear factor sets matter.
        require(abs(a[pivot][col]) > 1e-12 * scale) {
            "factors are collinear, or one is constant and so collinear with the intercept: the regression has no unique solution"
        }
        a[col] = a[pivot].also { a[pivot] = a[col] }
        inverse[col] = inverse[pivot].also { inverse[pivot] = inverse[col] }
        val divisor = a[col][col]
        for (j in 0 until n) {
            a[col][j] /= divisor
            inverse[col][j] /= divisor
        }
        for (row in 0 until n) {
            if (row == col) continue
            val factor = a[row][col]
            for (j in 0 until n) {
                a[row][j] -= factor * a[col][j]
                inverse[row][j] -= factor * inverse[col][j]
            }
        }
    }
    return inverse
}

private fun Array<DoubleArray>.times(other: Array<DoubleArray>) =
    Array(size) { i -> DoubleArray(other[0].size) { j -> other.indices.sumOf { m -> this[i][m] * other[m][j] } } }

private fun Array<DoubleArray>.scaled(factor: Double) = Array(size) { i -> DoubleArray(this[i].size) { j -> this[i][j] * factor } }
