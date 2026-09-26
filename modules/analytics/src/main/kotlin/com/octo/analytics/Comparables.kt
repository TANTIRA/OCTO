package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Currency

/** Formula set every comparables valuation below is computed under (quantitative-methodology.md §5.3, §10.5). */
const val COMPARABLES_METHODOLOGY = "quantitative-methodology §5.3 v1"

// ponytail: fixed floor for a percentile range; make it part of the declared policy if a desk needs fewer peers.
private const val MIN_PEERS = 3

private val LOW = BigDecimal("0.25")
private val MEDIAN = BigDecimal("0.5")
private val HIGH = BigDecimal("0.75")

/** How far-off peer multiples are treated before the range is taken. Declared, and stored with the run (§5.3). */
sealed interface OutlierPolicy {
    /** Every peer counts. */
    data object KeepAll : OutlierPolicy

    /** Drops multiples outside Tukey's fences [Q1 − k·IQR, Q3 + k·IQR], with the quartiles of every peer. */
    data class TukeyFences(
        val k: BigDecimal,
    ) : OutlierPolicy {
        init {
            require(k.signum() > 0) { "k must be positive" }
        }
    }
}

/** One peer's multiple of the chosen metric, e.g. EV/EBITDA. Peers where it is not meaningful are left out. */
data class PeerMultiple(
    val peer: String,
    val multiple: BigDecimal,
) {
    init {
        require(peer.isNotBlank()) { "a peer must be named" }
        require(multiple.signum() > 0) { "$peer: a multiple must be positive; leave not-meaningful peers out" }
    }
}

/** A named adjustment to the implied value, applied as a factor: a 20% illiquidity discount is 0.8. */
data class ValuationAdjustment(
    val reason: String,
    val factor: BigDecimal,
) {
    init {
        require(reason.isNotBlank()) { "an adjustment needs its reason" }
        require(factor.signum() > 0) { "an adjustment factor must be positive" }
    }
}

/** Inputs to a §5.3 run. [metric] and [metricPeriod] name what [companyMetric] and the peer multiples measure. */
data class ComparablesInputs(
    val valuationDate: LocalDate,
    val currency: Currency,
    val metric: String,
    val metricPeriod: String,
    val companyMetric: BigDecimal,
    val peers: List<PeerMultiple>,
    val outlierPolicy: OutlierPolicy,
    val adjustments: List<ValuationAdjustment> = emptyList(),
) {
    init {
        require(companyMetric.signum() > 0) { "the company metric must be positive for a multiple to apply" }
        require(peers.map { it.peer }.toSet().size == peers.size) { "each peer may appear once" }
    }
}

/** The peer range and the values it implies, with the run's inputs (§10.6) and which peers the policy excluded. */
data class ComparablesValuation(
    val inputs: ComparablesInputs,
    val usedPeers: List<PeerMultiple>,
    val excludedPeers: List<PeerMultiple>,
    val lowMultiple: BigDecimal,
    val medianMultiple: BigDecimal,
    val highMultiple: BigDecimal,
    val lowValue: BigDecimal,
    val medianValue: BigDecimal,
    val highValue: BigDecimal,
    val methodology: String = COMPARABLES_METHODOLOGY,
)

/**
 * §5.3: ImpliedEV = Metric_company × Multiple_peer, taken at the 25th, 50th and 75th percentile of the peer multiples
 * left after the outlier policy, then scaled by every adjustment.
 */
fun comparables(inputs: ComparablesInputs): ComparablesValuation {
    val all = inputs.peers.map { it.multiple }.sorted()
    val (used, excluded) =
        when (val policy = inputs.outlierPolicy) {
            OutlierPolicy.KeepAll -> inputs.peers to emptyList()
            is OutlierPolicy.TukeyFences -> {
                val q1 = percentile(all, LOW)
                val q3 = percentile(all, HIGH)
                val reach = policy.k * (q3 - q1)
                inputs.peers.partition { it.multiple >= q1 - reach && it.multiple <= q3 + reach }
            }
        }
    require(used.size >= MIN_PEERS) { "a range needs at least $MIN_PEERS peers, but ${used.size} remain" }

    val multiples = used.map { it.multiple }.sorted()
    val factor = inputs.adjustments.fold(BigDecimal.ONE) { product, adjustment -> product * adjustment.factor }
    val (low, median, high) = listOf(LOW, MEDIAN, HIGH).map { percentile(multiples, it) }
    return ComparablesValuation(
        inputs = inputs,
        usedPeers = used,
        excludedPeers = excluded,
        lowMultiple = low,
        medianMultiple = median,
        highMultiple = high,
        lowValue = inputs.companyMetric * low * factor,
        medianValue = inputs.companyMetric * median * factor,
        highValue = inputs.companyMetric * high * factor,
    )
}

/**
 * The [p]-quantile of [sorted] by linear interpolation between order statistics: Excel's PERCENTILE.INC and NumPy's
 * default. Exact in decimal for the quartiles.
 */
internal fun percentile(
    sorted: List<BigDecimal>,
    p: BigDecimal,
): BigDecimal {
    require(sorted.isNotEmpty()) { "no values" }
    val rank = BigDecimal(sorted.size - 1) * p
    val below = rank.setScale(0, RoundingMode.FLOOR).toInt()
    if (below + 1 >= sorted.size) return sorted[below]
    return sorted[below] + (rank - BigDecimal(below)) * (sorted[below + 1] - sorted[below])
}
