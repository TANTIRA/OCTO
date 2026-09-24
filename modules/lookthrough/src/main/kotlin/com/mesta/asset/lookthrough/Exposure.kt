package com.mesta.asset.lookthrough

import java.math.BigDecimal
import java.math.MathContext
import java.util.Currency

/**
 * [holder] owns [fraction] of [held]. A negative fraction is a short position.
 * Entity ids are opaque: fund, vehicle, deal and company all look the same here.
 */
data class OwnershipEdge(
    val holder: String,
    val held: String,
    val fraction: BigDecimal,
) {
    init {
        require(holder != held) { "$holder cannot own itself" }
        require(fraction.abs() <= BigDecimal.ONE) { "ownership of $held by $holder must be within -1..1" }
    }
}

/**
 * Look-through exposure of one root, in the root NAV's currency, with the measures the Marquee report
 * service defines (gross, net, long, short, asset counts).
 */
data class ExposureReport(
    val root: String,
    val currency: Currency,
    val byAsset: Map<String, BigDecimal>,
) {
    val netExposure: BigDecimal get() = byAsset.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val longExposure: BigDecimal get() = byAsset.values.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
    val shortExposure: BigDecimal get() = byAsset.values.filter { it.signum() < 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
    val grossExposure: BigDecimal get() = longExposure - shortExposure
    val assetCount: Int get() = byAsset.count { it.value.signum() != 0 }
    val assetCountLong: Int get() = byAsset.count { it.value.signum() > 0 }
    val assetCountShort: Int get() = byAsset.count { it.value.signum() < 0 }
}

/**
 * Methodology §7.2: exposure to each terminal holding is the sum, over every ownership path from
 * [root], of the product of ownerships times [rootNav]. All paths are summed, so a company reached
 * through two funds is counted through both. A cycle in the ownership graph is an error, not a loop.
 */
fun lookThrough(
    root: String,
    rootNav: BigDecimal,
    currency: Currency,
    edges: List<OwnershipEdge>,
): ExposureReport {
    val children = edges.groupBy { it.holder }
    val exposures = linkedMapOf<String, BigDecimal>()

    // ponytail: enumerates every path, exponential on deep diamond-shaped graphs; switch to a topological
    // sum of per-node amounts when real hierarchies get that dense.

    fun walk(
        node: String,
        amount: BigDecimal,
        path: List<String>,
    ) {
        check(node !in path) { "ownership cycle: ${(path + node).joinToString(" -> ")}" }
        val out = children[node]
        if (out.isNullOrEmpty()) {
            exposures.merge(node, amount, BigDecimal::add)
            return
        }
        for (edge in out) walk(edge.held, amount.multiply(edge.fraction, MathContext.DECIMAL64), path + node)
    }

    walk(root, rootNav, emptyList())
    return ExposureReport(root, currency, exposures)
}
