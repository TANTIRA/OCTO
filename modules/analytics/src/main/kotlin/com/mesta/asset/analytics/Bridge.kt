package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Currency

/** Formula set every bridge below is computed under (quantitative-methodology.md §4.3, §10.5). */
const val BRIDGE_METHODOLOGY = "quantitative-methodology §4.3 v1"

private val MC = MathContext.DECIMAL64

/** The value drivers §4.3 attributes a change in equity value to. */
enum class BridgeDriver { REVENUE, MARGIN, MULTIPLE, NET_DEBT, FX }

/**
 * The drivers at one end of the bridge. Amounts are in the company's local currency and [fxRate] converts them
 * to the reporting currency. EV = revenue × EBITDA margin × EV/EBITDA multiple; for a company valued on revenue,
 * set [margin] to 1 and [multiple] to EV/Revenue, and the margin effect is zero.
 */
data class BridgePoint(
    val date: LocalDate,
    val revenue: BigDecimal,
    val margin: BigDecimal,
    val multiple: BigDecimal,
    val netDebt: BigDecimal,
    val fxRate: BigDecimal,
) {
    init {
        require(revenue.signum() >= 0) { "revenue must not be negative" }
        require(multiple.signum() >= 0) { "multiple must not be negative" }
        require(fxRate.signum() > 0) { "fx rate must be positive" }
    }

    /** In the local currency. */
    val enterpriseValue: BigDecimal get() = revenue * margin * multiple

    /** In the reporting currency. Exact, so sequential effects add up to the change without rounding. */
    val equityValue: BigDecimal get() = (enterpriseValue - netDebt) * fxRate
}

/** How the change is attributed. The result carries it, so a sequential bridge keeps its ordering (§4.3). */
sealed interface BridgeMethod {
    /** Drivers switch from entry to exit values in [ordering]; each effect depends on that order. */
    data class Sequential(
        val ordering: List<BridgeDriver>,
    ) : BridgeMethod {
        init {
            require(ordering.size == BridgeDriver.entries.size && ordering.toSet() == BridgeDriver.entries.toSet()) {
                "ordering must name every driver exactly once"
            }
        }
    }

    /** Order-neutral: each driver's Shapley value over every subset of drivers. */
    data object Shapley : BridgeMethod
}

/** §4.3 attribution of the change in equity value from [entry] to [exit], in the reporting currency. */
data class ValueBridge(
    val localCurrency: Currency,
    val reportingCurrency: Currency,
    val entry: BridgePoint,
    val exit: BridgePoint,
    val method: BridgeMethod,
    val effects: Map<BridgeDriver, BigDecimal>,
    val methodology: String = BRIDGE_METHODOLOGY,
) {
    val change: BigDecimal get() = exit.equityValue - entry.equityValue
}

fun valueBridge(
    entry: BridgePoint,
    exit: BridgePoint,
    localCurrency: Currency,
    reportingCurrency: Currency,
    method: BridgeMethod,
): ValueBridge {
    require(entry.date.isBefore(exit.date)) { "entry must come before exit" }
    if (localCurrency == reportingCurrency) {
        require(entry.fxRate.compareTo(BigDecimal.ONE) == 0 && exit.fxRate.compareTo(BigDecimal.ONE) == 0) {
            "fx rates must be 1 when the local and reporting currencies are the same"
        }
    }
    val effects =
        when (method) {
            is BridgeMethod.Sequential -> sequential(entry, exit, method.ordering)
            BridgeMethod.Shapley -> shapley(entry, exit)
        }
    return ValueBridge(localCurrency, reportingCurrency, entry, exit, method, effects)
}

private fun sequential(
    entry: BridgePoint,
    exit: BridgePoint,
    ordering: List<BridgeDriver>,
): Map<BridgeDriver, BigDecimal> {
    val switched = mutableSetOf<BridgeDriver>()
    var before = entry.equityValue
    return ordering.associateWith { driver ->
        switched += driver
        val after = mix(entry, exit, switched).equityValue
        (after - before).also { before = after }
    }
}

/** φ_i = Σ_{S ⊆ N∖{i}} |S|!(n − |S| − 1)!/n! · [v(S ∪ {i}) − v(S)], with each subset S as a bitmask over the drivers. */
private fun shapley(
    entry: BridgePoint,
    exit: BridgePoint,
): Map<BridgeDriver, BigDecimal> {
    val drivers = BridgeDriver.entries
    val n = drivers.size
    val value = List(1 shl n) { mask -> mix(entry, exit, drivers.filterIndexed { i, _ -> (mask and (1 shl i)) != 0 }.toSet()).equityValue }
    val factorial = List(n + 1) { k -> (1..k).fold(1L) { product, j -> product * j } }
    return drivers.withIndex().associate { (i, driver) ->
        val weighted =
            (0 until (1 shl n)).filter { (it and (1 shl i)) == 0 }.sumOf { s ->
                val size = s.countOneBits()
                (value[s or (1 shl i)] - value[s]) * BigDecimal(factorial[size] * factorial[n - size - 1])
            }
        driver to weighted.divide(BigDecimal(factorial[n]), MC)
    }
}

/** [entry] with the drivers in [switched] moved to their [exit] values. */
private fun mix(
    entry: BridgePoint,
    exit: BridgePoint,
    switched: Set<BridgeDriver>,
) = entry.copy(
    revenue = if (BridgeDriver.REVENUE in switched) exit.revenue else entry.revenue,
    margin = if (BridgeDriver.MARGIN in switched) exit.margin else entry.margin,
    multiple = if (BridgeDriver.MULTIPLE in switched) exit.multiple else entry.multiple,
    netDebt = if (BridgeDriver.NET_DEBT in switched) exit.netDebt else entry.netDebt,
    fxRate = if (BridgeDriver.FX in switched) exit.fxRate else entry.fxRate,
)
