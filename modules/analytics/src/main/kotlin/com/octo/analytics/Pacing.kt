package com.octo.analytics

import java.math.BigDecimal
import java.math.MathContext

/** Commitment pacing identities (quantitative-methodology.md §2.3). */
private val MC = MathContext.DECIMAL64

/**
 * Committed − called. A negative result means the inputs disagree (called exceeds committed — a data
 * error, not an over-commitment), so it is rejected rather than clamped to a comforting zero.
 */
fun unfundedCommitment(
    committed: BigDecimal,
    called: BigDecimal,
): BigDecimal {
    require(committed.signum() >= 0 && called.signum() >= 0) { "commitments and calls are never negative" }
    return (committed - called).also { require(it.signum() >= 0) { "called capital exceeds committed" } }
}

/**
 * Called / committed. Null on a non-positive commitment — nothing was pledged, so "how much of it is
 * drawn" has no answer, and 0% would read as "not yet called" rather than "no commitment exists".
 */
fun drawdownRate(
    committed: BigDecimal,
    called: BigDecimal,
): BigDecimal? {
    require(called.signum() >= 0) { "called capital is never negative" }
    if (committed.signum() <= 0) return null
    require(committed - called >= BigDecimal.ZERO) { "called capital exceeds committed" }
    return called.divide(committed, MC)
}

/**
 * D / called. Null on non-positive called — distributions without paid-in capital are an input error
 * the caller should see, not a rate it should read.
 */
fun distributionRate(
    called: BigDecimal,
    distributed: BigDecimal,
): BigDecimal? {
    require(distributed.signum() >= 0) { "distributions are never negative" }
    if (called.signum() <= 0) return null
    return distributed.divide(called, MC)
}
