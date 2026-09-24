package com.mesta.asset.analytics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Baum–Welch (EM) from [initial] over a fully observed series (§9.1). Each iteration re-estimates π, A, μ and
 * σ from the smoothed state and transition probabilities and never lowers the likelihood. [minimumSigma]
 * stops a regime collapsing onto one observation; the caller declares it. EM finds a local optimum, so the
 * caller starts it from several initial models and keeps the best (§9.1: not in-sample fit alone).
 */
fun baumWelch(
    initial: HiddenMarkovModel,
    observations: List<RegimeObservation>,
    minimumSigma: Double,
    tolerance: Double = 1e-8,
    maxIterations: Int = 500,
): EstimatedModel {
    require(observations.size >= 2) { "estimation needs at least two observations" }
    require(
        observations.all { it.value != null },
    ) { "estimation needs a fully observed series; drop or impute gaps explicitly first (§10.7)" }
    require(minimumSigma > 0 && minimumSigma.isFinite()) { "minimumSigma must be positive" }
    require(tolerance > 0 && maxIterations > 0) { "tolerance and maxIterations must be positive" }
    val y = observations.map { it.value!! }
    val k = initial.states
    val last = observations.lastIndex

    var model = initial
    var logLikelihood = regimeFilter(model, observations).logLikelihood
    var iterations = 0
    var converged = false
    while (iterations < maxIterations) {
        iterations++
        // E-step: γ_t(i) = P(S_t = i | y), ξ_t(i, j) = P(S_t = i, S_{t+1} = j | y).
        val alpha = regimeFilter(model, observations).estimates.map { it.filtered }
        val beta = backward(model, observations)
        val gamma = observations.indices.map { t -> normalize((0 until k).map { alpha[t][it] * beta[t][it] }) }
        val xiSum = Array(k) { DoubleArray(k) }
        for (t in 0 until last) {
            val emission = emission(model, y[t + 1])
            val xi =
                (0 until k).flatMap { i ->
                    (0 until k).map { j -> alpha[t][i] * model.transition[i][j] * emission[j] * beta[t + 1][j] }
                }
            val total = xi.sum()
            for (i in 0 until k) for (j in 0 until k) xiSum[i][j] += xi[i * k + j] / total
        }
        // M-step.
        val occupancy = (0 until k).map { i -> observations.indices.sumOf { gamma[it][i] } }
        val transition =
            (0 until k).map { i ->
                val leaving = (0 until last).sumOf { gamma[it][i] }
                (0 until k).map { j -> if (leaving > 0) xiSum[i][j] / leaving else model.transition[i][j] }
            }
        val regimes =
            (0 until k).map { i ->
                val mean = observations.indices.sumOf { gamma[it][i] * y[it] } / occupancy[i]
                val variance = observations.indices.sumOf { gamma[it][i] * (y[it] - mean) * (y[it] - mean) } / occupancy[i]
                GaussianRegime(model.regimes[i].name, mean, max(sqrt(variance), minimumSigma))
            }
        model = HiddenMarkovModel(regimes, transition, gamma[0])

        val updated = regimeFilter(model, observations).logLikelihood
        val gain = updated - logLikelihood
        logLikelihood = updated
        if (abs(gain) < tolerance) {
            converged = true
            break
        }
    }
    return EstimatedModel(model, logLikelihood, iterations, converged)
}
