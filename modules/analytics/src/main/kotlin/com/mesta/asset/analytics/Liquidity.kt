package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Currency

/** Formula set every result below is computed under (quantitative-methodology.md §7.3, §10.5). */
const val LIQUIDITY_METHODOLOGY = "quantitative-methodology §7.3 v1"

private val MC = MathContext.DECIMAL64

/** A non-negative amount in its own currency. */
data class Amount(
    val currency: Currency,
    val value: BigDecimal,
) {
    init {
        require(value.signum() >= 0) { "amounts are magnitudes and must not be negative" }
    }
}

/** The four §7.3 terms at [asOf], with forecasts running to [horizonEnd]. */
data class LiquidityPosition(
    val asOf: LocalDate,
    val horizonEnd: LocalDate,
    val liquidAssets: List<Amount>,
    val forecastInflows: List<Amount>,
    val unfundedCommitments: List<Amount>,
    val forecastOutflows: List<Amount>,
) {
    init {
        require(!horizonEnd.isBefore(asOf)) { "the horizon must not end before the as-of date" }
    }
}

/**
 * A §7.3 stress. Each term is multiplied by its factor, and [fxShocks] multiply a currency's rate into the
 * reporting currency (0.9 is a 10% fall of that currency). The methodology names the stresses but not their
 * size, so the scenario states them: lower liquid-asset values cut [liquidAssets], delayed distributions cut
 * [forecastInflows], and accelerated calls raise whichever obligation term the caller forecasts them in.
 */
data class LiquidityStress(
    val name: String,
    val liquidAssets: BigDecimal = BigDecimal.ONE,
    val forecastInflows: BigDecimal = BigDecimal.ONE,
    val unfundedCommitments: BigDecimal = BigDecimal.ONE,
    val forecastOutflows: BigDecimal = BigDecimal.ONE,
    val fxShocks: Map<Currency, BigDecimal> = emptyMap(),
) {
    init {
        val factors = listOf(liquidAssets, forecastInflows, unfundedCommitments, forecastOutflows)
        require(factors.all { it.signum() >= 0 }) { "$name: stress factors must not be negative" }
        require(fxShocks.values.all { it.signum() > 0 }) { "$name: FX shocks must be positive" }
    }

    companion object {
        val BASE = LiquidityStress("base")
    }
}

/**
 * §7.3 terms in [reportingCurrency]. A term is null when one of its currencies has no FX rate. [ratio] is null
 * when a term is undefined or nothing is owed: coverage of nothing is undefined, never infinite (§10.7).
 */
data class CoverageReport(
    val asOf: LocalDate,
    val horizonEnd: LocalDate,
    val reportingCurrency: Currency,
    val scenario: String,
    val liquidAssets: BigDecimal?,
    val forecastInflows: BigDecimal?,
    val unfundedCommitments: BigDecimal?,
    val forecastOutflows: BigDecimal?,
    val ratio: BigDecimal?,
    val methodology: String = LIQUIDITY_METHODOLOGY,
)

/**
 * CoverageRatio = (LiquidAssets + ForecastInflows) / (UnfundedCommitments + ForecastOutflows), methodology §7.3,
 * under [stress]. [fxRates] convert one unit of each currency into [reportingCurrency], which needs no entry.
 */
fun coverage(
    position: LiquidityPosition,
    reportingCurrency: Currency,
    fxRates: Map<Currency, BigDecimal>,
    stress: LiquidityStress = LiquidityStress.BASE,
): CoverageReport {
    require(fxRates.values.all { it.signum() > 0 }) { "FX rates must be positive" }
    require(reportingCurrency !in stress.fxShocks) { "shock the other currencies; the reporting currency's rate is 1" }

    fun rate(currency: Currency): BigDecimal? =
        if (currency == reportingCurrency) {
            BigDecimal.ONE
        } else {
            fxRates[currency]?.multiply(stress.fxShocks[currency] ?: BigDecimal.ONE, MC)
        }

    fun term(
        amounts: List<Amount>,
        factor: BigDecimal,
    ): BigDecimal? {
        val converted = amounts.map { amount -> rate(amount.currency)?.let { amount.value.multiply(it, MC) } }
        if (null in converted) return null
        return converted.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add).multiply(factor, MC)
    }

    val liquidAssets = term(position.liquidAssets, stress.liquidAssets)
    val forecastInflows = term(position.forecastInflows, stress.forecastInflows)
    val unfundedCommitments = term(position.unfundedCommitments, stress.unfundedCommitments)
    val forecastOutflows = term(position.forecastOutflows, stress.forecastOutflows)
    val covered = if (liquidAssets == null || forecastInflows == null) null else liquidAssets + forecastInflows
    val owed = if (unfundedCommitments == null || forecastOutflows == null) null else unfundedCommitments + forecastOutflows

    return CoverageReport(
        asOf = position.asOf,
        horizonEnd = position.horizonEnd,
        reportingCurrency = reportingCurrency,
        scenario = stress.name,
        liquidAssets = liquidAssets,
        forecastInflows = forecastInflows,
        unfundedCommitments = unfundedCommitments,
        forecastOutflows = forecastOutflows,
        ratio = if (covered == null || owed == null || owed.signum() == 0) null else covered.divide(owed, MC),
    )
}
