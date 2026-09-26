package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Formula set every result below is computed under (quantitative-methodology.md §6, §10.5). */
const val FORECAST_METHODOLOGY = "quantitative-methodology §6 v1"

private val MC = MathContext.DECIMAL64

/**
 * X_t / X_{t−1} − 1. Null for a non-positive base: growth from zero is undefined, and from a negative base the
 * formula inverts the sign (−10 → +5 reads as −150%), so no number is better than a misleading one.
 */
fun growth(
    previous: BigDecimal,
    current: BigDecimal,
): BigDecimal? = if (previous.signum() <= 0) null else current.divide(previous, MC) - BigDecimal.ONE

/** (X_T / X_0)^(1/n) − 1 over [periods]. Null for a non-positive start or a negative end: no real rate exists. */
fun cagr(
    start: BigDecimal,
    end: BigDecimal,
    periods: Double,
): Double? {
    requireFinite("periods", listOf(periods))
    require(periods > 0.0) { "CAGR needs a positive number of periods" }
    if (start.signum() <= 0 || end.signum() < 0) return null
    return (end.toDouble() / start.toDouble()).pow(1.0 / periods) - 1.0
}

/** EBITDA / Revenue. Null on zero revenue. */
fun ebitdaMargin(
    ebitda: BigDecimal,
    revenue: BigDecimal,
): BigDecimal? {
    require(revenue.signum() >= 0) { "revenue must not be negative" }
    return if (revenue.signum() == 0) null else ebitda.divide(revenue, MC)
}

/** FCF = EBIT(1 − T_c) + D&A − Capex − ΔNWC. [capex] is spend as a positive amount; [deltaNwc] is the increase in NWC. */
fun freeCashFlow(
    ebit: BigDecimal,
    taxRate: BigDecimal,
    depreciationAndAmortization: BigDecimal,
    capex: BigDecimal,
    deltaNwc: BigDecimal,
): BigDecimal {
    require(taxRate.signum() >= 0 && taxRate <= BigDecimal.ONE) { "tax rate must be within 0..1" }
    return ebit.multiply(BigDecimal.ONE - taxRate, MC) + depreciationAndAmortization - capex - deltaNwc
}

/** ŷ = Σ ω_k ŷ_k over the same models in [forecasts] and [weights], with Σ ω_k = 1. */
fun weightedForecast(
    forecasts: Map<String, Double>,
    weights: Map<String, Double>,
): Double {
    require(forecasts.isNotEmpty()) { "at least one model forecast is required" }
    require(forecasts.keys == weights.keys) { "every model needs exactly one weight: ${forecasts.keys} vs ${weights.keys}" }
    requireFinite("forecasts", forecasts.values)
    requireFinite("weights", weights.values)
    require(abs(weights.values.sum() - 1.0) <= 1e-9) { "weights sum to ${weights.values.sum()}, not 1" }
    return forecasts.entries.sumOf { (model, forecast) -> weights.getValue(model) * forecast }
}

data class ForecastErrors(
    val count: Int,
    val mae: Double,
    val rmse: Double,
    val methodology: String = FORECAST_METHODOLOGY,
)

/** MAE and RMSE of [predicted] against [actual], pairwise. */
fun forecastErrors(
    actual: List<Double>,
    predicted: List<Double>,
): ForecastErrors {
    require(actual.isNotEmpty() && actual.size == predicted.size) { "need the same, non-zero number of actuals and predictions" }
    requireFinite("actuals", actual)
    requireFinite("predictions", predicted)
    val errors = actual.zip(predicted) { y, yHat -> y - yHat }
    return ForecastErrors(
        count = errors.size,
        mae = errors.sumOf { abs(it) } / errors.size,
        rmse = sqrt(errors.sumOf { it * it } / errors.size),
    )
}

/**
 * Walk-forward validation (§6.2). At each origin t from [minTrain] on, [forecaster] receives only series[0, t) and
 * predicts series[t + horizon − 1], so no forecast can use an observation from its own future. Errors are scored
 * over those out-of-sample predictions only. Null when the series is too short for a single origin.
 */
fun walkForward(
    series: List<Double>,
    minTrain: Int,
    horizon: Int = 1,
    forecaster: (history: List<Double>) -> Double,
): ForecastErrors? {
    require(minTrain >= 1) { "the forecaster needs at least one observation" }
    require(horizon >= 1) { "the horizon must be at least one step" }
    requireFinite("series", series)
    val origins = minTrain..(series.size - horizon)
    if (origins.isEmpty()) return null
    val actual = origins.map { series[it + horizon - 1] }
    val predicted = origins.map { forecaster(series.subList(0, it).toList()) }
    return forecastErrors(actual, predicted)
}

/**
 * One §6.3 update with what the methodology says to store: prior, evidence timestamp, data vintage, posterior, and
 * analyst override. An override needs a rationale, the way a superseding ledger row does; [effective] is what to use.
 */
data class BayesianUpdate(
    val hypothesis: String,
    val prior: Double,
    val likelihood: Double,
    val evidenceProbability: Double,
    val evidenceAt: Instant,
    val dataVintage: LocalDate,
    val posterior: Double,
    val analystOverride: Double? = null,
    val overrideRationale: String? = null,
    val methodology: String = FORECAST_METHODOLOGY,
) {
    init {
        requireProbability("prior", prior)
        requireProbability("likelihood", likelihood)
        requireProbability("evidence probability", evidenceProbability)
        requireProbability("posterior", posterior)
        require(abs(posterior - likelihood * prior / evidenceProbability) <= 1e-12) { "posterior does not follow from Bayes' rule" }
        analystOverride?.let { requireProbability("analyst override", it) }
        require(analystOverride == null || !overrideRationale.isNullOrBlank()) { "an analyst override needs a rationale" }
    }

    val effective: Double get() = analystOverride ?: posterior

    fun overriddenBy(
        value: Double,
        rationale: String,
    ): BayesianUpdate = copy(analystOverride = value, overrideRationale = rationale)
}

/** P(H | E) = P(E | H) P(H) / P(E). Rejects a P(E) below P(E | H) P(H), which no joint distribution allows. */
fun bayesianUpdate(
    hypothesis: String,
    prior: Double,
    likelihood: Double,
    evidenceProbability: Double,
    evidenceAt: Instant,
    dataVintage: LocalDate,
): BayesianUpdate {
    requireProbability("evidence probability", evidenceProbability)
    require(evidenceProbability > 0.0) { "evidence with probability zero cannot update anything" }
    require(evidenceProbability >= likelihood * prior - 1e-12) { "P(E) is below P(E|H)·P(H): the inputs are inconsistent" }
    return BayesianUpdate(
        hypothesis = hypothesis,
        prior = prior,
        likelihood = likelihood,
        evidenceProbability = evidenceProbability,
        evidenceAt = evidenceAt,
        dataVintage = dataVintage,
        posterior = (likelihood * prior / evidenceProbability).coerceAtMost(1.0),
    )
}

/** P(E) by total probability: P(E | H) P(H) + P(E | ¬H) (1 − P(H)). */
fun evidenceProbability(
    prior: Double,
    likelihoodIfTrue: Double,
    likelihoodIfFalse: Double,
): Double {
    listOf(prior, likelihoodIfTrue, likelihoodIfFalse).forEach { requireProbability("probability", it) }
    return likelihoodIfTrue * prior + likelihoodIfFalse * (1.0 - prior)
}

private fun requireProbability(
    name: String,
    p: Double,
) = require(p.isFinite() && p in 0.0..1.0) { "$name must be a probability within 0..1, was $p" }
