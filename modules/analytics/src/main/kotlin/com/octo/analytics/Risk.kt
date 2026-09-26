package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.sqrt

/** Formula set every result below is computed under (quantitative-methodology.md §3, §10.5). */
const val RISK_METHODOLOGY = "quantitative-methodology §3 v1"

private val MC = MathContext.DECIMAL64

/**
 * Where the values behind a return series come from. Appraisal NAVs are smoothed, so volatility and
 * drawdown computed from them understate the real figures and must be labeled as such (methodology §3.3).
 */
enum class ValuationBasis { MARKET, APPRAISAL }

/** Residual NAV on [date], after any cash flow dated that day. */
data class NavObservation(
    val date: LocalDate,
    val nav: BigDecimal,
)

/**
 * Time-weighted return over [navs] (methodology §3.1): Π(1 + r_t) − 1 with
 * r_t = (V_t − V_{t−1} − NetFlow_t) / V_{t−1}.
 *
 * [flows] carry the investor sign of §10.2, so NetFlow_t, the net flow into the portfolio, is minus the
 * flows dated d_t. Subperiods are separated by external flows: every flow after the first observation must
 * fall on an observation date, because moving a dated flow to a period end is the approximation §10.1
 * forbids. Flows on or before the first observation are already in V_0. Null when a subperiod starts from
 * a zero NAV: its return is undefined, never zero.
 */
fun timeWeightedReturn(
    navs: List<NavObservation>,
    flows: List<CashFlow>,
): BigDecimal? {
    require(navs.size >= 2) { "TWR needs at least two NAV observations" }
    require(navs.zipWithNext().all { (a, b) -> a.date.isBefore(b.date) }) { "NAV observations must have strictly increasing dates" }
    require(navs.all { it.nav.signum() >= 0 }) { "NAV must not be negative" }

    val start = navs.first().date
    val netInflow =
        flows
            .filter { it.date.isAfter(start) }
            .groupBy({ it.date }, { it.amount })
            .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO, BigDecimal::add).negate() }
    val unobserved = netInflow.keys - navs.map { it.date }.toSet()
    require(unobserved.isEmpty()) { "no NAV observation on flow dates ${unobserved.sorted()}; TWR subperiods must end at each flow" }

    var growth = BigDecimal.ONE
    for ((previous, current) in navs.zipWithNext()) {
        if (previous.nav.signum() == 0) return null
        val flow = netInflow[current.date] ?: BigDecimal.ZERO
        growth = growth.multiply((current.nav - flow).divide(previous.nav, MC), MC)
    }
    return growth - BigDecimal.ONE
}

/**
 * Methodology §3.2–3.4 measures of one series of equally spaced returns, such as quarterly TWRs. Every
 * measure is per period of that series: nothing is annualized. A null field is undefined, never a zero.
 * VaR (one period ahead) and ES are fractions of the starting value; multiply by V_0 for an amount.
 */
data class RiskReport(
    val basis: ValuationBasis,
    val periods: Int,
    val mean: Double?,
    val volatility: Double?,
    val sharpe: Double?,
    val sortino: Double?,
    val maxDrawdown: Double?,
    val confidence: Double,
    val valueAtRisk: Double?,
    val expectedShortfall: Double?,
    val methodology: String = RISK_METHODOLOGY,
)

/** [riskFree] and [target] are per-period rates, in the units of [returns]. */
fun riskReport(
    returns: List<Double>,
    basis: ValuationBasis,
    riskFree: Double,
    target: Double,
    confidence: Double,
): RiskReport =
    RiskReport(
        basis = basis,
        periods = returns.size,
        mean = if (returns.isEmpty()) null else returns.average(),
        volatility = volatility(returns),
        sharpe = sharpe(returns, riskFree),
        sortino = sortino(returns, target),
        maxDrawdown = maxDrawdown(returns),
        confidence = confidence,
        valueAtRisk = parametricVar(returns, confidence),
        expectedShortfall = historicalExpectedShortfall(returns, confidence),
    )

/** NaN and infinity are how a missing or broken value arrives in a Double; §10.7 forbids turning one into a result. */
internal fun requireFinite(
    name: String,
    values: Collection<Double>,
) = require(values.all { it.isFinite() }) { "$name must be finite: NaN or infinity is a missing value, not a number (§10.7)" }

/** Sample standard deviation (n − 1): the series estimates the return process rather than being all of it. */
fun volatility(returns: List<Double>): Double? {
    requireFinite("returns", returns)
    if (returns.size < 2) return null
    val mean = returns.average()
    return sqrt(returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1))
}

/** §3.2: σ_p = √(wᵀΣw). Only the symmetric part of [covariance] affects the result. */
fun portfolioVolatility(
    weights: List<Double>,
    covariance: List<List<Double>>,
): Double {
    val n = weights.size
    require(covariance.size == n && covariance.all { it.size == n }) { "covariance must be $n×$n" }
    requireFinite("weights", weights)
    requireFinite("covariance", covariance.flatten())
    val variance = (0 until n).sumOf { i -> (0 until n).sumOf { j -> weights[i] * covariance[i][j] * weights[j] } }
    // Rounding can leave a fully hedged portfolio a hair below zero; anything further is not a covariance.
    require(variance > -1e-12) { "covariance is not positive semi-definite: wᵀΣw = $variance" }
    return sqrt(variance.coerceAtLeast(0.0))
}

/** §3.3: E[R_p − R_f] / σ_p with a constant per-period [riskFree]. */
fun sharpe(
    returns: List<Double>,
    riskFree: Double,
): Double? {
    requireFinite("risk-free rate", listOf(riskFree))
    val sigma = volatility(returns)?.takeIf { it > 0.0 } ?: return null
    return (returns.average() - riskFree) / sigma
}

/** §3.3: E[R_p − R_target] / √E[min(R_p − R_target, 0)²], both expectations over every period. */
fun sortino(
    returns: List<Double>,
    target: Double,
): Double? {
    requireFinite("returns", returns)
    requireFinite("target", listOf(target))
    if (returns.isEmpty()) return null
    val downside = sqrt(returns.sumOf { minOf(it - target, 0.0).let { shortfall -> shortfall * shortfall } } / returns.size)
    if (downside == 0.0) return null
    return (returns.average() - target) / downside
}

/**
 * §3.3 maximum drawdown of the wealth index Π(1 + r) starting at 1. Taking it on the return index rather
 * than on NAV keeps capital calls and distributions from reading as gains and drawdowns.
 */
fun maxDrawdown(returns: List<Double>): Double? {
    requireFinite("returns", returns)
    if (returns.isEmpty()) return null
    var value = 1.0
    var peak = 1.0
    var worst = 0.0
    for (r in returns) {
        value *= 1 + r
        peak = maxOf(peak, value)
        worst = maxOf(worst, (peak - value) / peak)
    }
    return worst
}

/**
 * §3.4 parametric normal VaR over [horizon] periods, as a fraction of V_0: z_α·σ·√h − μ·h, with μ and σ
 * estimated from [returns]. Negative when the expected gain outweighs the tail at this confidence.
 */
fun parametricVar(
    returns: List<Double>,
    confidence: Double,
    horizon: Int = 1,
): Double? {
    require(horizon > 0) { "horizon must be at least one period" }
    val sigma = volatility(returns) ?: return null
    return normalQuantile(confidence) * sigma * sqrt(horizon.toDouble()) - returns.average() * horizon
}

/**
 * §3.4 historical expected shortfall −E[R | R ≤ q_{1−α}], positive for a loss. q_{1−α} is the
 * ⌈n(1 − α)⌉-th smallest return; returns tied with it are in the tail too.
 */
fun historicalExpectedShortfall(
    returns: List<Double>,
    confidence: Double,
): Double? {
    require(confidence > 0.0 && confidence < 1.0) { "confidence must be between 0 and 1" }
    requireFinite("returns", returns)
    if (returns.isEmpty()) return null
    // Decimal arithmetic so that, say, 5% of 100 returns is exactly 5 and not 5.000000000000004.
    val tailSize =
        (BigDecimal.ONE - BigDecimal.valueOf(confidence))
            .multiply(BigDecimal(returns.size))
            .setScale(0, RoundingMode.CEILING)
            .toInt()
    val quantile = returns.sorted()[tailSize - 1]
    return -returns.filter { it <= quantile }.average()
}

// Acklam's coefficients, highest order first; each denominator's constant term is 1.
private val CENTRAL_NUMERATOR =
    doubleArrayOf(-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.357751867269, -30.66479806614716, 2.506628277459239)
private val CENTRAL_DENOMINATOR =
    doubleArrayOf(-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572, 1.0)
private val TAIL_NUMERATOR =
    doubleArrayOf(-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783)
private val TAIL_DENOMINATOR =
    doubleArrayOf(0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416, 1.0)

/** Standard normal quantile (Acklam's rational approximation, relative error below 1.2e-9). */
internal fun normalQuantile(p: Double): Double {
    require(p > 0.0 && p < 1.0) { "probability must be between 0 and 1" }
    val low = 0.02425
    return when {
        p < low -> tail(sqrt(-2 * ln(p)))
        p > 1 - low -> -tail(sqrt(-2 * ln(1 - p)))
        else -> (p - 0.5).let { q -> q * horner(q * q, CENTRAL_NUMERATOR) / horner(q * q, CENTRAL_DENOMINATOR) }
    }
}

private fun tail(q: Double) = horner(q, TAIL_NUMERATOR) / horner(q, TAIL_DENOMINATOR)

private fun horner(
    x: Double,
    coefficients: DoubleArray,
) = coefficients.fold(0.0) { acc, c -> acc * x + c }
