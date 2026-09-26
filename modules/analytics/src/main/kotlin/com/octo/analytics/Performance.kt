package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency
import kotlin.math.pow

/** Formula set every result below is computed under (quantitative-methodology.md §2, §10.5). */
const val PERFORMANCE_METHODOLOGY = "quantitative-methodology §2 v1"

/**
 * One dated investor cash flow. Sign follows methodology §10.2: contributions negative,
 * distributions positive.
 */
data class CashFlow(
    val date: LocalDate,
    val amount: BigDecimal,
)

/**
 * An investor's dated cash flows in one currency, with residual NAV at [valuationDate].
 * Mixed currencies are rejected rather than silently summed (methodology §10.3).
 */
data class CashFlowSeries(
    val currency: Currency,
    val flows: List<CashFlow>,
    val nav: BigDecimal,
    val valuationDate: LocalDate,
) {
    init {
        require(flows.all { !it.date.isAfter(valuationDate) }) { "cash flows after the valuation date leak future data" }
        require(nav.signum() >= 0) { "NAV must not be negative" }
    }

    /** PIC: contributions as a positive amount. */
    val paidIn: BigDecimal get() = flows.filter { it.amount.signum() < 0 }.sumOf { it.amount.negate() }

    /** D: cumulative distributions. */
    val distributed: BigDecimal get() = flows.filter { it.amount.signum() > 0 }.sumOf { it.amount }
}

/** Methodology §2.1–2.2. A null field is undefined for these inputs, never a zero. */
data class PerformanceReport(
    val currency: Currency,
    val valuationDate: LocalDate,
    val paidIn: BigDecimal,
    val distributed: BigDecimal,
    val nav: BigDecimal,
    val dpi: BigDecimal?,
    val rvpi: BigDecimal?,
    val tvpi: BigDecimal?,
    val irr: Double?,
    val methodology: String = PERFORMANCE_METHODOLOGY,
)

private val MC = MathContext.DECIMAL64

private fun ratio(
    numerator: BigDecimal,
    denominator: BigDecimal,
): BigDecimal? = if (denominator.signum() == 0) null else numerator.divide(denominator, MC)

/** DPI, RVPI, TVPI and IRR at the series valuation date. */
fun performance(series: CashFlowSeries): PerformanceReport {
    val pic = series.paidIn
    val d = series.distributed
    return PerformanceReport(
        currency = series.currency,
        valuationDate = series.valuationDate,
        paidIn = pic,
        distributed = d,
        nav = series.nav,
        dpi = ratio(d, pic),
        rvpi = ratio(series.nav, pic),
        tvpi = ratio(d + series.nav, pic),
        irr = xirr(series.flows + CashFlow(series.valuationDate, series.nav)),
    )
}

/**
 * Methodology §10.4: portfolio metrics come from the funds' pooled cash flows, never from averaging
 * fund results. Flows are concatenated, not netted by date, so paid-in and distributions stay gross.
 * Series must share one currency (§10.3) and one valuation date: NAVs from different dates don't add.
 */
fun pool(series: List<CashFlowSeries>): CashFlowSeries {
    require(series.isNotEmpty()) { "nothing to pool" }
    val first = series.first()
    require(series.all { it.currency == first.currency }) { "pooling across currencies needs an FX source (§10.3)" }
    require(series.all { it.valuationDate == first.valuationDate }) { "pooled series must share one valuation date" }
    return CashFlowSeries(
        currency = first.currency,
        flows = series.flatMap { it.flows }.sortedBy { it.date },
        nav = series.sumOf { it.nav },
        valuationDate = first.valuationDate,
    )
}

/**
 * XIRR with an actual/365 year fraction (methodology §2.1).
 *
 * Returns null when the NPV curve does not cross zero exactly once over (-99%, +10,000%). A pattern
 * with several roots has no single meaningful rate, so the function reports none instead of picking one.
 */
fun xirr(flows: List<CashFlow>): Double? {
    val nonZero = flows.filter { it.amount.signum() != 0 }
    if (nonZero.none { it.amount.signum() < 0 } || nonZero.none { it.amount.signum() > 0 }) return null
    val d0 = nonZero.minOf { it.date }
    val points = nonZero.map { ChronoUnit.DAYS.between(d0, it.date) / 365.0 to it.amount.toDouble() }
    val npv = { r: Double -> points.sumOf { (t, c) -> c / (1 + r).pow(t) } }

    // ponytail: fixed grid scan, two roots closer than one grid step read as none; refine the grid if that bites.
    val grid = (0..400).map { -0.99 + (101.0 - -0.99) * (it / 400.0).pow(3) }
    val crossings = grid.zipWithNext().filter { (a, b) -> npv(a) == 0.0 || npv(a) * npv(b) < 0 }
    if (crossings.size != 1) return null
    var (lo, hi) = crossings.single()
    repeat(200) {
        val mid = (lo + hi) / 2
        if (npv(lo) * npv(mid) <= 0) hi = mid else lo = mid
    }
    return (lo + hi) / 2
}

/**
 * Kaplan-Schoar PME (methodology §2.4). [benchmark] maps each date to an index level and must cover
 * every flow date plus the valuation date. A missing level makes the result undefined (§10.7).
 */
fun ksPme(
    series: CashFlowSeries,
    benchmark: Map<LocalDate, BigDecimal>,
): BigDecimal? {
    val scaled = scaleToValuationDate(series, benchmark) ?: return null
    val contributions = scaled.filter { it.amount.signum() < 0 }.sumOf { it.amount.negate() }
    val distributions = scaled.filter { it.amount.signum() > 0 }.sumOf { it.amount }
    return ratio(distributions + series.nav, contributions)
}

/** Direct Alpha (methodology §2.5): the IRR of benchmark-scaled flows plus terminal NAV. */
fun directAlpha(
    series: CashFlowSeries,
    benchmark: Map<LocalDate, BigDecimal>,
): Double? {
    val scaled = scaleToValuationDate(series, benchmark) ?: return null
    return xirr(scaled + CashFlow(series.valuationDate, series.nav))
}

private fun scaleToValuationDate(
    series: CashFlowSeries,
    benchmark: Map<LocalDate, BigDecimal>,
): List<CashFlow>? {
    val bT = benchmark[series.valuationDate] ?: return null
    return series.flows.map { flow ->
        val bt = benchmark[flow.date]?.takeIf { it.signum() > 0 } ?: return null
        flow.copy(amount = flow.amount.multiply(bT, MC).divide(bt, MC))
    }
}

/** Methodology §2.3, at LP level. */
data class CommitmentStatus(
    val committed: BigDecimal,
    val called: BigDecimal,
    val unfunded: BigDecimal,
    val drawdownRate: BigDecimal?,
    val distributionRate: BigDecimal?,
)

fun commitmentStatus(
    committed: BigDecimal,
    series: CashFlowSeries,
): CommitmentStatus {
    require(committed.signum() >= 0) { "commitment must not be negative" }
    val called = series.paidIn
    return CommitmentStatus(
        committed = committed,
        called = called,
        unfunded = committed - called,
        drawdownRate = ratio(called, committed),
        distributionRate = ratio(series.distributed, called),
    )
}
