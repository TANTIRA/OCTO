package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Currency

/** Formula set every attribution below is computed under (quantitative-methodology.md §4.2, §10.5). */
const val ATTRIBUTION_METHODOLOGY = "quantitative-methodology §4.2 v1"

private val MC = MathContext.DECIMAL64

// ponytail: fixed tolerance for upstream rounding of weights; make it a parameter if a source needs a looser one.
private val WEIGHT_TOLERANCE = BigDecimal("1e-9")

/**
 * One sector's weights and period returns. A return may be null only where its weight is zero:
 * the portfolio does not hold a benchmark sector, or the benchmark does not hold a portfolio sector.
 */
data class SectorPerformance(
    val sector: String,
    val portfolioWeight: BigDecimal,
    val benchmarkWeight: BigDecimal,
    val portfolioReturn: BigDecimal?,
    val benchmarkReturn: BigDecimal?,
) {
    init {
        require(portfolioReturn != null || portfolioWeight.signum() == 0) { "$sector is held but has no portfolio return" }
        require(benchmarkReturn != null || benchmarkWeight.signum() == 0) { "$sector is in the benchmark but has no benchmark return" }
    }
}

/** Methodology §4.2 effects for one sector. A null effect is undefined for these inputs, never a zero. */
data class SectorAttribution(
    val sector: String,
    val allocation: BigDecimal?,
    val selection: BigDecimal?,
    val interaction: BigDecimal?,
)

data class AttributionReport(
    val currency: Currency,
    val benchmark: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val portfolioReturn: BigDecimal,
    val benchmarkReturn: BigDecimal,
    val sectors: List<SectorAttribution>,
    val methodology: String = ATTRIBUTION_METHODOLOGY,
) {
    val activeReturn: BigDecimal get() = portfolioReturn - benchmarkReturn
    val allocation: BigDecimal? get() = total { it.allocation }
    val selection: BigDecimal? get() = total { it.selection }
    val interaction: BigDecimal? get() = total { it.interaction }

    /** Null when any sector's effect is undefined: a partial sum would read as the whole. */
    private fun total(effect: (SectorAttribution) -> BigDecimal?): BigDecimal? {
        val values = sectors.map(effect)
        return if (null in values) null else values.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add)
    }
}

/**
 * Single-period Brinson attribution (methodology §4.2). Portfolio and benchmark weights must each sum
 * to one, so allocation + selection + interaction equals the active return. Multi-period linking is not
 * defined in the methodology and is not done here.
 */
fun brinson(
    currency: Currency,
    benchmark: String,
    periodStart: LocalDate,
    periodEnd: LocalDate,
    sectors: List<SectorPerformance>,
): AttributionReport {
    require(periodStart.isBefore(periodEnd)) { "period must start before it ends" }
    require(sectors.isNotEmpty()) { "at least one sector is required" }
    require(sectors.map { it.sector }.toSet().size == sectors.size) { "sectors must be unique" }
    requireWeightsSumToOne("portfolio", sectors.map { it.portfolioWeight })
    requireWeightsSumToOne("benchmark", sectors.map { it.benchmarkWeight })

    val rp = sectors.sumOf { times(it.portfolioWeight, it.portfolioReturn)!! }
    val rb = sectors.sumOf { times(it.benchmarkWeight, it.benchmarkReturn)!! }
    return AttributionReport(
        currency = currency,
        benchmark = benchmark,
        periodStart = periodStart,
        periodEnd = periodEnd,
        portfolioReturn = rp,
        benchmarkReturn = rb,
        sectors =
            sectors.map { s ->
                val activeWeight = s.portfolioWeight - s.benchmarkWeight
                val sectorExcess = s.portfolioReturn?.let { rps -> s.benchmarkReturn?.let { rps - it } }
                SectorAttribution(
                    sector = s.sector,
                    allocation = times(activeWeight, s.benchmarkReturn?.minus(rb)),
                    selection = times(s.benchmarkWeight, sectorExcess),
                    interaction = times(activeWeight, sectorExcess),
                )
            },
    )
}

/** A zero weight contributes zero even where its return is undefined; otherwise undefined stays undefined. */
private fun times(
    weight: BigDecimal,
    value: BigDecimal?,
): BigDecimal? = if (weight.signum() == 0) BigDecimal.ZERO else value?.multiply(weight, MC)

private fun requireWeightsSumToOne(
    side: String,
    weights: List<BigDecimal>,
) {
    val sum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
    require((sum - BigDecimal.ONE).abs() <= WEIGHT_TOLERANCE) { "$side weights sum to $sum, not 1" }
}
