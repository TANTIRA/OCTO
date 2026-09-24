package com.mesta.asset.analytics

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt

/** Formula set every estimate below is computed under (quantitative-methodology.md §9.3, §10.5). */
const val KALMAN_METHODOLOGY = "quantitative-methodology §9.3 v1"

/** A Gaussian belief about the latent state: mean x̂ and covariance P. */
data class GaussianState(
    val mean: List<Double>,
    val covariance: List<List<Double>>,
) {
    init {
        val n = mean.size
        require(n > 0) { "the state needs at least one dimension" }
        require(covariance.size == n && covariance.all { it.size == n }) { "covariance must be $n×$n" }
        requireFinite("state mean", mean)
        requireFinite("state covariance", covariance.flatten())
    }
}

/**
 * The time-invariant system of §9.3: x_t = F·x_{t−1} + B·u_t + w_t with w ~ N(0, Q), and y_t = H·x_t + v_t
 * with v ~ N(0, R). [control] is null when there is no control input.
 */
data class LinearSystem(
    val transition: List<List<Double>>,
    val processNoise: List<List<Double>>,
    val observation: List<List<Double>>,
    val observationNoise: List<List<Double>>,
    val control: List<List<Double>>? = null,
) {
    val stateSize = transition.size
    val observationSize = observation.size

    init {
        require(stateSize > 0 && transition.all { it.size == stateSize }) { "F must be square and non-empty" }
        require(processNoise.size == stateSize && processNoise.all { it.size == stateSize }) { "Q must be $stateSize×$stateSize" }
        require(observationSize > 0 && observation.all { it.size == stateSize }) { "H must be m×$stateSize with m > 0" }
        require(observationNoise.size == observationSize && observationNoise.all { it.size == observationSize }) {
            "R must be $observationSize×$observationSize"
        }
        require(control == null || (control.size == stateSize && control.all { it.isNotEmpty() && it.size == control[0].size })) {
            "B must be $stateSize×k"
        }
        requireFinite("system", (transition + processNoise + observation + observationNoise + (control ?: emptyList())).flatten())
    }
}

/**
 * One period's data. A null [measurement] is a missing observation (§10.7): the filter predicts through
 * it and skips the update. [controlInput] is required exactly when the system has a control matrix.
 */
data class KalmanObservation(
    val date: LocalDate,
    val measurement: List<Double>?,
    val controlInput: List<Double>? = null,
)

/**
 * The filter's output for one period. [predicted] is x̂_{t|t−1}; [filtered] is x̂_{t|t} and is the only
 * estimate valid as a live signal. [innovation], [innovationCovariance] and the standardized innovation
 * ỹ_t / √diag(S_t) (§9.4) are null when the measurement was missing, as is the period's [logLikelihood].
 */
data class KalmanEstimate(
    val date: LocalDate,
    val predicted: GaussianState,
    val filtered: GaussianState,
    val innovation: List<Double>?,
    val innovationCovariance: List<List<Double>>?,
    val standardizedInnovation: List<Double>?,
    val logLikelihood: Double?,
)

data class KalmanResult(
    val estimates: List<KalmanEstimate>,
    /** Σ_t log p(y_t | y_{1:t−1}) over the observed periods: the §9.6 log-likelihood diagnostic. */
    val logLikelihood: Double,
    val methodology: String = KALMAN_METHODOLOGY,
)

/** §9.3 prediction and Joseph-form update, run forward from [initial] (the belief before the first period). */
fun kalmanFilter(
    system: LinearSystem,
    initial: GaussianState,
    observations: List<KalmanObservation>,
): KalmanResult {
    require(initial.mean.size == system.stateSize) { "initial state must have ${system.stateSize} dimensions" }
    require(observations.zipWithNext().all { (a, b) -> a.date.isBefore(b.date) }) { "observations must have strictly increasing dates" }
    val f = system.transition.toArray()
    val q = system.processNoise.toArray()
    val h = system.observation.toArray()
    val r = system.observationNoise.toArray()
    val b = system.control?.toArray()
    val identity = Array(system.stateSize) { i -> DoubleArray(system.stateSize) { j -> if (i == j) 1.0 else 0.0 } }

    var mean = initial.mean.toDoubleArray()
    var cov = initial.covariance.toArray()
    var total = 0.0
    val estimates =
        observations.map { observation ->
            require((observation.controlInput != null) == (b != null)) {
                "${observation.date}: a control input is required exactly when the system has B"
            }
            require(observation.measurement == null || observation.measurement.size == system.observationSize) {
                "${observation.date}: measurement must have ${system.observationSize} dimensions"
            }
            // Prediction.
            var predictedMean = f.times(mean)
            if (b != null) predictedMean = predictedMean.plus(b.times(observation.controlInput!!.toDoubleArray()))
            val predictedCov = f.times(cov).times(f.transpose()).plus(q)
            val predicted = GaussianState(predictedMean.toList(), predictedCov.toLists())

            val y = observation.measurement
            if (y == null) {
                mean = predictedMean
                cov = predictedCov
                return@map KalmanEstimate(observation.date, predicted, predicted, null, null, null, null)
            }
            requireFinite("measurement on ${observation.date}", y)
            // Innovation and its covariance.
            val innovation = y.toDoubleArray().minus(h.times(predictedMean))
            val s = h.times(predictedCov).times(h.transpose()).plus(r)
            val chol = cholesky(s) { "${observation.date}: innovation covariance is not positive definite" }
            // Update, Joseph form: P = (I − KH) P (I − KH)ᵀ + K R Kᵀ.
            val gain = predictedCov.times(h.transpose()).times(chol.inverse())
            mean = predictedMean.plus(gain.times(innovation))
            val ikh = identity.minus(gain.times(h))
            cov = ikh.times(predictedCov).times(ikh.transpose()).plus(gain.times(r).times(gain.transpose()))
            val whitened = chol.solve(innovation)
            val logLikelihood =
                -0.5 * (y.size * ln(2 * PI) + chol.logDeterminant() + innovation.indices.sumOf { innovation[it] * whitened[it] })
            total += logLikelihood
            KalmanEstimate(
                date = observation.date,
                predicted = predicted,
                filtered = GaussianState(mean.toList(), cov.toLists()),
                innovation = innovation.toList(),
                innovationCovariance = s.toLists(),
                standardizedInnovation = innovation.indices.map { innovation[it] / sqrt(s[it][it]) },
                logLikelihood = logLikelihood,
            )
        }
    return KalmanResult(estimates, total)
}

/** Lower-triangular L with L·Lᵀ = A, for a symmetric positive-definite A. Solves and inverts without forming A⁻¹ unstably. */
internal class Cholesky(
    private val l: Array<DoubleArray>,
) {
    fun solve(rhs: DoubleArray): DoubleArray {
        val n = l.size
        val z = DoubleArray(n)
        for (i in 0 until n) z[i] = (rhs[i] - (0 until i).sumOf { l[i][it] * z[it] }) / l[i][i]
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) x[i] = (z[i] - (i + 1 until n).sumOf { l[it][i] * x[it] }) / l[i][i]
        return x
    }

    fun inverse(): Array<DoubleArray> {
        val n = l.size
        val columns = Array(n) { j -> solve(DoubleArray(n) { i -> if (i == j) 1.0 else 0.0 }) }
        return Array(n) { i -> DoubleArray(n) { j -> columns[j][i] } }
    }

    fun logDeterminant() = 2 * l.indices.sumOf { ln(l[it][it]) }
}

internal fun cholesky(
    a: Array<DoubleArray>,
    message: () -> String,
): Cholesky {
    val n = a.size
    val l = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0..i) {
            val sum = (a[i][j] + a[j][i]) / 2 - (0 until j).sumOf { l[i][it] * l[j][it] }
            if (i == j) {
                require(sum > 0) { message() }
                l[i][i] = sqrt(sum)
            } else {
                l[i][j] = sum / l[j][j]
            }
        }
    }
    return Cholesky(l)
}

internal fun List<List<Double>>.toArray() = Array(size) { this[it].toDoubleArray() }

internal fun Array<DoubleArray>.toLists() = map { it.toList() }

internal fun Array<DoubleArray>.transpose() = Array(this[0].size) { j -> DoubleArray(size) { i -> this[i][j] } }

internal fun Array<DoubleArray>.times(other: Array<DoubleArray>) =
    Array(size) { i -> DoubleArray(other[0].size) { j -> other.indices.sumOf { m -> this[i][m] * other[m][j] } } }

internal fun Array<DoubleArray>.times(vector: DoubleArray) = DoubleArray(size) { i -> vector.indices.sumOf { this[i][it] * vector[it] } }

internal fun Array<DoubleArray>.plus(other: Array<DoubleArray>) =
    Array(size) { i ->
        DoubleArray(this[i].size) { j ->
            this[i][j] +
                other[i][j]
        }
    }

internal fun Array<DoubleArray>.minus(other: Array<DoubleArray>) =
    Array(size) { i ->
        DoubleArray(this[i].size) { j ->
            this[i][j] -
                other[i][j]
        }
    }

internal fun DoubleArray.plus(other: DoubleArray) = DoubleArray(size) { this[it] + other[it] }

internal fun DoubleArray.minus(other: DoubleArray) = DoubleArray(size) { this[it] - other[it] }
