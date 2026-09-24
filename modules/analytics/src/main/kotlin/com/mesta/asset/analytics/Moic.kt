package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.util.Currency

/** Formula set every result below is computed under (quantitative-methodology.md §2.2, §10.5). */
const val MOIC_METHODOLOGY = "quantitative-methodology §2.2 v1"

/**
 * Deal-level multiples (§2.2). MOIC divides by invested capital at the deal level and is gross of fund
 * fees; TVPI divides by paid-in capital at fund or LP level. They are not interchangeable and this type
 * exists so a screen cannot confuse them with [PerformanceReport]. A null multiple is undefined: nothing
 * invested, or no valuation for the unrealized part (§10.7), never a zero.
 */
data class DealMultiples(
    val currency: Currency,
    val invested: BigDecimal,
    val realized: BigDecimal,
    val unrealized: BigDecimal?,
    val moic: BigDecimal?,
    val realizedMultiple: BigDecimal?,
    val unrealizedMultiple: BigDecimal?,
    val methodology: String = MOIC_METHODOLOGY,
)

/** MOIC = (Realized + Unrealized) / Invested, with the realized and unrealized parts shown separately (§2.2). */
fun moic(
    currency: Currency,
    invested: BigDecimal,
    realized: BigDecimal,
    unrealized: BigDecimal?,
): DealMultiples {
    require(invested.signum() >= 0 && realized.signum() >= 0 && (unrealized == null || unrealized.signum() >= 0)) {
        "invested, realized and unrealized are magnitudes and must not be negative"
    }
    val mc = MathContext.DECIMAL64

    fun over(numerator: BigDecimal?) = if (numerator == null || invested.signum() == 0) null else numerator.divide(invested, mc)
    return DealMultiples(
        currency = currency,
        invested = invested,
        realized = realized,
        unrealized = unrealized,
        moic = over(unrealized?.let { realized + it }),
        realizedMultiple = over(realized),
        unrealizedMultiple = over(unrealized),
    )
}
