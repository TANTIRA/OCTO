package com.mesta.asset.analytics

import kotlin.math.abs
import kotlin.math.sqrt

/** Formula set every state-space result below is computed under (quantitative-methodology.md §9.3–9.4, §10.5). */
const val STATE_SPACE_METHODOLOGY = "quantitative-methodology §9.3–9.4 v1"

/**
 * A linear Gaussian state-space model (§9.3): x_t = F x_{t−1} + w_t with w ~ N(0, Q), and y_t = H_t x_t + v_t with
 * v ~ N(0, R). F, Q and R are constant; the observation matrix H_t may change per step, as §9.4's regression needs.
 */
class KalmanModel(
    val transition: Array<DoubleArray>,
    val processNoise: Array<DoubleArray>,
    val measurementNoise: Array<DoubleArray>,
    val initialState: DoubleArray,
    val initialCovariance: Array<DoubleArray>,
) {
    init {
        val n = initialState.size
        require(n > 0) { "the state needs at least one dimension" }
        requireSquare("transition", transition, n)
        requireCovariance("process noise", processNoise, n)
        requireCovariance("initial covariance", initialCovariance, n)
        requireCovariance("measurement noise", measurementNoise, measurementNoise.size)
        requireFinite("initial state", initialState.toList())
    }
}

/**
 * Filtered output, point in time: every value at step t uses observations up to t only, so it is valid as a live
 * signal (§9.1, §9.3). [innovations] and [innovationCovariances] are ỹ_t and S_t, the inputs to §9.6 diagnostics.
 */
class KalmanFiltered(
    val model: KalmanModel,
    val predictedStates: List<DoubleArray>,
    val predictedCovariances: List<Array<DoubleArray>>,
    val states: List<DoubleArray>,
    val covariances: List<Array<DoubleArray>>,
    val innovations: List<DoubleArray>,
    val innovationCovariances: List<Array<DoubleArray>>,
    val methodology: String = STATE_SPACE_METHODOLOGY,
)

/** Rauch–Tung–Striebel output. It uses the whole sample, so it is for historical analysis only, never a live signal (§9.3). */
class SmoothedHistoricalOnly(
    val states: List<DoubleArray>,
    val covariances: List<Array<DoubleArray>>,
    val methodology: String = STATE_SPACE_METHODOLOGY,
)

/** §9.3 predict and update for each observation, with the Joseph covariance update. H_t comes from [observationMatrix]. */
fun kalmanFilter(
    model: KalmanModel,
    observations: List<DoubleArray>,
    observationMatrix: (step: Int) -> Array<DoubleArray>,
): KalmanFiltered {
    require(observations.isNotEmpty()) { "at least one observation is required" }
    val n = model.initialState.size
    val m = model.measurementNoise.size
    val f = model.transition
    val identity = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
    var x = model.initialState
    var p = model.initialCovariance
    val predictedStates = mutableListOf<DoubleArray>()
    val predictedCovariances = mutableListOf<Array<DoubleArray>>()
    val states = mutableListOf<DoubleArray>()
    val covariances = mutableListOf<Array<DoubleArray>>()
    val innovations = mutableListOf<DoubleArray>()
    val innovationCovariances = mutableListOf<Array<DoubleArray>>()
    for ((t, y) in observations.withIndex()) {
        require(y.size == m) { "observation $t has ${y.size} values, not $m" }
        requireFinite("observation $t", y.toList())
        val h = observationMatrix(t)
        require(h.size == m && h.all { it.size == n }) { "H at step $t must be $m×$n" }
        requireFinite("H at step $t", h.flatMap { it.toList() })

        val xPredicted = f * x
        val pPredicted = f * p * f.transpose() + model.processNoise
        val innovation = DoubleArray(m) { i -> y[i] - (h * xPredicted)[i] }
        val s = h * pPredicted * h.transpose() + model.measurementNoise
        val gain = pPredicted * h.transpose() * inverseSpd(s, "innovation covariance at step $t")
        x = DoubleArray(n) { i -> xPredicted[i] + (gain * innovation)[i] }
        val a = identity - gain * h
        p = symmetrised(a * pPredicted * a.transpose() + gain * model.measurementNoise * gain.transpose())

        predictedStates += xPredicted
        predictedCovariances += pPredicted
        states += x
        covariances += p
        innovations += innovation
        innovationCovariances += s
    }
    return KalmanFiltered(model, predictedStates, predictedCovariances, states, covariances, innovations, innovationCovariances)
}

/** §9.3 backward pass: J_t = P_{t|t} Fᵀ P_{t+1|t}⁻¹, x̂_{t|T} = x̂_{t|t} + J_t (x̂_{t+1|T} − x̂_{t+1|t}). */
fun rtsSmooth(filtered: KalmanFiltered): SmoothedHistoricalOnly {
    val f = filtered.model.transition
    val last = filtered.states.lastIndex
    val states = filtered.states.toMutableList()
    val covariances = filtered.covariances.toMutableList()
    for (t in last - 1 downTo 0) {
        val j =
            filtered.covariances[t] * f.transpose() *
                inverseSpd(filtered.predictedCovariances[t + 1], "predicted covariance at step ${t + 1}")
        val stateGap = DoubleArray(states[t].size) { i -> states[t + 1][i] - filtered.predictedStates[t + 1][i] }
        states[t] = DoubleArray(states[t].size) { i -> filtered.states[t][i] + (j * stateGap)[i] }
        covariances[t] =
            symmetrised(filtered.covariances[t] + j * (covariances[t + 1] - filtered.predictedCovariances[t + 1]) * j.transpose())
    }
    return SmoothedHistoricalOnly(states, covariances)
}

/**
 * §9.4 time-varying hedge ratio: y_t = α_t + β_t x_t + v_t with [α, β] a random walk. [residuals] are
 * z_t = y_t − α̂_{t|t−1} − β̂_{t|t−1} x_t, and [standardizedResiduals] divide them by √S_t, not by a rolling deviation.
 */
class DynamicRegression(
    val alphas: List<Double>,
    val betas: List<Double>,
    val residuals: List<Double>,
    val standardizedResiduals: List<Double>,
    val methodology: String = STATE_SPACE_METHODOLOGY,
)

fun dynamicRegression(
    xs: List<Double>,
    ys: List<Double>,
    stateNoise: Array<DoubleArray>,
    measurementVariance: Double,
    initialState: DoubleArray,
    initialCovariance: Array<DoubleArray>,
): DynamicRegression {
    require(xs.size == ys.size) { "x and y must have the same length" }
    requireFinite("x", xs)
    val model =
        KalmanModel(
            arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0)),
            stateNoise,
            arrayOf(doubleArrayOf(measurementVariance)),
            initialState,
            initialCovariance,
        )
    val filtered = kalmanFilter(model, ys.map { doubleArrayOf(it) }) { t -> arrayOf(doubleArrayOf(1.0, xs[t])) }
    return DynamicRegression(
        alphas = filtered.states.map { it[0] },
        betas = filtered.states.map { it[1] },
        residuals = filtered.innovations.map { it[0] },
        standardizedResiduals =
            filtered.innovations.indices.map {
                filtered.innovations[it][0] /
                    sqrt(filtered.innovationCovariances[it][0][0])
            },
    )
}

private fun requireSquare(
    name: String,
    a: Array<DoubleArray>,
    n: Int,
) {
    require(a.size == n && a.all { it.size == n }) { "$name must be $n×$n" }
    requireFinite(name, a.flatMap { it.toList() })
}

private fun requireCovariance(
    name: String,
    a: Array<DoubleArray>,
    n: Int,
) {
    require(n > 0) { "$name must not be empty" }
    requireSquare(name, a, n)
    val scale = a.maxOf { row -> row.maxOf { abs(it) } }.coerceAtLeast(1.0)
    require((0 until n).all { i -> (0 until n).all { j -> abs(a[i][j] - a[j][i]) <= 1e-12 * scale } }) { "$name must be symmetric" }
}

/** Inverse of a symmetric positive-definite matrix by Cholesky; fails if it is not positive definite. */
private fun inverseSpd(
    a: Array<DoubleArray>,
    name: String,
): Array<DoubleArray> {
    val n = a.size
    val l = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0..i) {
            val sum = a[i][j] - (0 until j).sumOf { l[i][it] * l[j][it] }
            if (i == j) {
                require(sum > 0.0) { "$name is not positive definite" }
                l[i][i] = sqrt(sum)
            } else {
                l[i][j] = sum / l[j][j]
            }
        }
    }
    // Solve L Lᵀ X = I one column at a time.
    return Array(n) { DoubleArray(n) }.also { inverse ->
        for (c in 0 until n) {
            val z = DoubleArray(n)
            for (i in 0 until n) z[i] = ((if (i == c) 1.0 else 0.0) - (0 until i).sumOf { l[i][it] * z[it] }) / l[i][i]
            for (i in n - 1 downTo 0) inverse[i][c] = (z[i] - (i + 1 until n).sumOf { l[it][i] * inverse[it][c] }) / l[i][i]
        }
    }
}

private fun symmetrised(a: Array<DoubleArray>) = Array(a.size) { i -> DoubleArray(a.size) { j -> (a[i][j] + a[j][i]) / 2 } }

private fun Array<DoubleArray>.transpose() = Array(this[0].size) { j -> DoubleArray(size) { i -> this[i][j] } }

private operator fun Array<DoubleArray>.times(other: Array<DoubleArray>) =
    Array(size) { i -> DoubleArray(other[0].size) { j -> other.indices.sumOf { k -> this[i][k] * other[k][j] } } }

private operator fun Array<DoubleArray>.times(v: DoubleArray) = DoubleArray(size) { i -> v.indices.sumOf { k -> this[i][k] * v[k] } }

private operator fun Array<DoubleArray>.plus(other: Array<DoubleArray>) =
    Array(size) { i ->
        DoubleArray(this[i].size) { j ->
            this[i][j] +
                other[i][j]
        }
    }

private operator fun Array<DoubleArray>.minus(other: Array<DoubleArray>) =
    Array(size) { i ->
        DoubleArray(this[i].size) { j ->
            this[i][j] -
                other[i][j]
        }
    }
