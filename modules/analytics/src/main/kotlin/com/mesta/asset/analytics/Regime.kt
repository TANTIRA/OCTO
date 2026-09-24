package com.mesta.asset.analytics

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Formula set every estimate below is computed under (quantitative-methodology.md §9.1–9.2, §10.5). */
const val REGIME_METHODOLOGY = "quantitative-methodology §9.1–9.2 v1"

/** A regime with Gaussian emissions y | S = k ~ N(μ_k, σ_k²): the two-state return model of §9.2. */
data class GaussianRegime(
    val name: String,
    val mean: Double,
    val standardDeviation: Double,
) {
    init {
        require(mean.isFinite() && standardDeviation.isFinite() && standardDeviation > 0) { "$name: σ must be finite and positive" }
    }

    internal fun logDensity(y: Double) = -0.5 * ln(2 * PI) - ln(standardDeviation) - 0.5 * ((y - mean) / standardDeviation).let { it * it }
}

/** §9.1: regimes, transition matrix A with a_ij = P(S_t = j | S_{t−1} = i), and initial probabilities π. */
data class HiddenMarkovModel(
    val regimes: List<GaussianRegime>,
    val transition: List<List<Double>>,
    val initial: List<Double>,
) {
    val states = regimes.size

    init {
        require(states > 0) { "at least one regime is needed" }
        require(regimes.map { it.name }.toSet().size == states) { "regime names must be distinct" }
        require(transition.size == states && transition.all { it.size == states }) { "A must be $states×$states" }
        require(initial.size == states) { "π must have $states entries" }
        (transition + listOf(initial)).forEachIndexed { i, row ->
            requireFinite("probabilities", row)
            require(
                row.all { it >= 0 } && abs(row.sum() - 1) < 1e-9,
            ) { "${if (i < states) "row $i of A" else "π"} must be a probability distribution" }
        }
    }

    /** §9.1 expected duration 1 / (1 − a_ii); null for an absorbing regime, whose duration is unbounded. */
    fun expectedDurations(): List<Double?> = (0 until states).map { i -> if (transition[i][i] >= 1) null else 1 / (1 - transition[i][i]) }

    /** §9.2 predicted regime distribution P(S_{t+1} = j | y_{1:t}) = Σ_i a_ij P(S_t = i | y_{1:t}). */
    fun predictNext(filtered: List<Double>): List<Double> =
        (0 until states).map { j ->
            (0 until states).sumOf { i ->
                filtered[i] *
                    transition[i][j]
            }
        }

    /** §9.2 probability-weighted forecast E[y_{t+1} | y_{1:t}] = Σ_j P(S_{t+1} = j | y_{1:t}) μ_j. */
    fun forecast(predictedNext: List<Double>): Double = (0 until states).sumOf { predictedNext[it] * regimes[it].mean }
}

/** One period's value; null is a missing observation (§10.7), which updates the regime only through A. */
data class RegimeObservation(
    val date: LocalDate,
    val value: Double?,
)

/**
 * §9.1 filtered probabilities P(S_t | y_{1:t}), the only ones valid in live decisions, with the §9.2
 * one-step prediction P(S_{t+1} | y_{1:t}). [logLikelihood] is log p(y_t | y_{1:t−1}); null when missing.
 */
data class RegimeEstimate(
    val date: LocalDate,
    val filtered: List<Double>,
    val predictedNext: List<Double>,
    val logLikelihood: Double?,
)

data class RegimeResult(
    val estimates: List<RegimeEstimate>,
    /** Σ_t log p(y_t | y_{1:t−1}) over observed periods: the §9.6 log-likelihood diagnostic. */
    val logLikelihood: Double,
    val methodology: String = REGIME_METHODOLOGY,
)

/**
 * A §9.1 maximum-likelihood estimate. [converged] is false when [iterations] hit the cap before the
 * likelihood gain fell under the tolerance; the model is then the best found, not the optimum.
 */
data class EstimatedModel(
    val model: HiddenMarkovModel,
    val logLikelihood: Double,
    val iterations: Int,
    val converged: Boolean,
    val methodology: String = REGIME_METHODOLOGY,
)

/** §9.1 forward recursion, normalized at each step. */
fun regimeFilter(
    model: HiddenMarkovModel,
    observations: List<RegimeObservation>,
): RegimeResult {
    validate(observations)
    var predicted = model.initial
    var total = 0.0
    val estimates =
        observations.map { observation ->
            val (filtered, logLikelihood) = update(model, predicted, observation.value)
            logLikelihood?.let { total += it }
            predicted = model.predictNext(filtered)
            RegimeEstimate(observation.date, filtered, predicted, logLikelihood)
        }
    return RegimeResult(estimates, total)
}

/**
 * §9.1 smoothed probabilities P(S_t | y_{1:T}) by the forward–backward recursion. They use future
 * observations: historical analysis, labelling and estimation only, never a live signal.
 */
fun regimeSmoother(
    model: HiddenMarkovModel,
    observations: List<RegimeObservation>,
): List<List<Double>> {
    val forward = regimeFilter(model, observations).estimates
    val backward = backward(model, observations)
    return observations.indices.map { t -> normalize((0 until model.states).map { forward[t].filtered[it] * backward[t][it] }) }
}

/** Normalized backward variables β̂_t; the scale is irrelevant because every use renormalizes. */
internal fun backward(
    model: HiddenMarkovModel,
    observations: List<RegimeObservation>,
): List<List<Double>> {
    val k = model.states
    val beta = arrayOfNulls<List<Double>>(observations.size)
    for (t in observations.indices.reversed()) {
        beta[t] =
            if (t == observations.lastIndex) {
                List(k) { 1.0 }
            } else {
                val emission = emission(model, observations[t + 1].value)
                normalize((0 until k).map { i -> (0 until k).sumOf { j -> model.transition[i][j] * emission[j] * beta[t + 1]!![j] } })
            }
    }
    return beta.map { it!! }
}

/** f(y; θ_j) per regime, scaled by the largest so nothing underflows; all ones for a missing value. */
internal fun emission(
    model: HiddenMarkovModel,
    y: Double?,
): List<Double> {
    if (y == null) return List(model.states) { 1.0 }
    val logs = model.regimes.map { it.logDensity(y) }
    val shift = logs.max()
    return logs.map { exp(it - shift) }
}

/** §9.1 Viterbi: the most likely regime sequence, as indices into [HiddenMarkovModel.regimes]. Historical only. */
fun viterbi(
    model: HiddenMarkovModel,
    observations: List<RegimeObservation>,
): List<Int> {
    validate(observations)
    if (observations.isEmpty()) return emptyList()
    val k = model.states

    fun logEmission(y: Double?) = List(k) { j -> y?.let { model.regimes[j].logDensity(it) } ?: 0.0 }
    val logA = model.transition.map { row -> row.map { ln(it) } }
    var delta = logEmission(observations[0].value).mapIndexed { j, e -> ln(model.initial[j]) + e }
    val back = mutableListOf<List<Int>>()
    for (t in 1 until observations.size) {
        val emission = logEmission(observations[t].value)
        val pointers = (0 until k).map { j -> (0 until k).maxBy { i -> delta[i] + logA[i][j] } }
        delta = (0 until k).map { j -> delta[pointers[j]] + logA[pointers[j]][j] + emission[j] }
        back += pointers
    }
    val path = IntArray(observations.size)
    path[path.lastIndex] = delta.indices.maxBy { delta[it] }
    for (t in path.lastIndex - 1 downTo 0) path[t] = back[t][path[t + 1]]
    return path.toList()
}

/** §9.2 probability-weighted target allocation w* = Σ_k P(S = k) w_k; [regimeWeights] holds one weight vector per regime. */
fun probabilityWeightedAllocation(
    probabilities: List<Double>,
    regimeWeights: List<List<Double>>,
): List<Double> {
    require(
        regimeWeights.size == probabilities.size &&
            regimeWeights.all {
                it.size == regimeWeights[0].size
            },
    ) { "one weight vector per regime" }
    return regimeWeights[0].indices.map { a -> probabilities.indices.sumOf { k -> probabilities[k] * regimeWeights[k][a] } }
}

/** §9.2 hysteresis: enter the regime when its probability reaches [enter], leave only when it falls below [exit] < [enter]. */
fun hysteresis(
    probabilities: List<Double>,
    enter: Double,
    exit: Double,
): List<Boolean> {
    require(exit < enter) { "h_exit must be below h_enter, or the policy has no hysteresis" }
    requireFinite("probabilities", probabilities)
    var active = false
    return probabilities.map { p ->
        active = if (active) p >= exit else p >= enter
        active
    }
}

private fun update(
    model: HiddenMarkovModel,
    predicted: List<Double>,
    y: Double?,
): Pair<List<Double>, Double?> {
    if (y == null) return predicted to null
    require(y.isFinite()) { "a NaN or infinite value is a missing observation: pass null (§10.7)" }
    // Scale by the largest log-density so the normalizer cannot underflow to zero.
    val logs = model.regimes.map { it.logDensity(y) }
    val shift = logs.max()
    val joint = predicted.indices.map { predicted[it] * exp(logs[it] - shift) }
    val normalizer = joint.sum()
    return normalize(joint) to ln(normalizer) + shift
}

internal fun normalize(v: List<Double>): List<Double> {
    val sum = v.sum()
    require(sum > 0) { "every regime has zero probability: the model cannot explain the observation" }
    return v.map { it / sum }
}

private fun validate(observations: List<RegimeObservation>) =
    require(observations.zipWithNext().all { (a, b) -> a.date.isBefore(b.date) }) { "observations must have strictly increasing dates" }
