package com.mesta.asset.recon

import com.mesta.asset.iborcore.InstrumentKey
import com.mesta.asset.iborcore.TokenPosition
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/*
 * Onchain reconciliation: what the provider reports a wallet holds vs what the append-only
 * ledger derived. Pure functions over fetched rows — this module never touches JDBC and never
 * writes corrections. A divergence is a report, not a mutation (megaplan §P5).
 */

/** One row of `mesta.onchain_balance_snapshot` — an observed holding, not a ledger fact. */
data class ObservedBalance(
    val chain: String,
    val wallet: String,
    val mintAddress: String?,
    val amountRaw: BigInteger,
    val decimals: Int,
    val asOf: Instant,
    val source: String,
)

/**
 * One compared holding. [deltaRaw] is `onchain − derived`: positive means the provider shows
 * more than the ledger explains (missed inbound fact), negative means the ledger credits more
 * (missed outbound or a bad fact). [unclassifiedRaw] carries the position's `other`-typed flow
 * volume — a position holding unresolved facts can never be reported clean.
 */
data class ReconDelta(
    val chain: String,
    val wallet: String,
    val instrumentId: UUID,
    val mintAddress: String?,
    val onchainRaw: BigInteger,
    val derivedRaw: BigInteger,
    val unclassifiedRaw: BigInteger,
    val deltaRaw: BigInteger,
    val decimals: Int,
    val asOf: Instant?,
)

/** The whole comparison for one run. [clean] is the only verdict callers should need. */
data class ReconReport(
    val deltas: List<ReconDelta>,
    val unregistered: List<ObservedBalance>,
) {
    val clean: Boolean
        get() =
            unregistered.isEmpty() &&
                deltas.all { it.deltaRaw.signum() == 0 && it.unclassifiedRaw.signum() == 0 }

    val divergent: List<ReconDelta> get() = deltas.filter { it.deltaRaw.signum() != 0 }

    val unverifiable: List<ReconDelta> get() = deltas.filter { it.unclassifiedRaw.signum() != 0 }
}

/**
 * Diffs each wallet's derived [TokenPosition]s against its latest observed balances.
 * [instruments] maps `(chain, mint)` to the registry id — snapshots join positions through it
 * because `instrument_flow` knows instrument ids while the provider knows mints.
 *
 * Missing sides count as zero: an onchain holding with no flows yields `delta = +onchain`
 * (unexplained holding); a derived position with no observation yields `delta = −derived`.
 */
fun reconcileOnchain(
    positions: List<TokenPosition>,
    snapshots: List<ObservedBalance>,
    instruments: Map<InstrumentKey, UUID>,
): ReconReport {
    val latest = mutableMapOf<Triple<String, String, String?>, ObservedBalance>()
    for (s in snapshots) {
        val key = Triple(s.chain, s.wallet, s.mintAddress)
        val current = latest[key]
        if (current == null || s.asOf.isAfter(current.asOf)) latest[key] = s
    }
    val byInstrument = instruments.entries.associate { (k, v) -> v to k }

    val deltas = mutableListOf<ReconDelta>()
    val seen = mutableSetOf<Pair<UUID, String>>()
    for (p in positions) {
        val key = byInstrument[p.instrumentId] ?: continue // flow on an instrument the registry forgot — impossible in practice
        val observed = latest.remove(Triple(key.chain, p.wallet, key.mintAddress))
        seen += p.instrumentId to p.wallet
        deltas +=
            ReconDelta(
                chain = key.chain,
                wallet = p.wallet,
                instrumentId = p.instrumentId,
                mintAddress = key.mintAddress,
                onchainRaw = observed?.amountRaw ?: BigInteger.ZERO,
                derivedRaw = p.netRaw,
                unclassifiedRaw = p.unclassifiedRaw,
                deltaRaw = (observed?.amountRaw ?: BigInteger.ZERO) - p.netRaw,
                decimals = observed?.decimals ?: p.decimals,
                asOf = observed?.asOf,
            )
    }
    val unregistered = mutableListOf<ObservedBalance>()
    for ((key, observed) in latest) {
        val (chain, wallet, mint) = key
        val instrumentId = instruments[InstrumentKey(chain, mint)]
        if (instrumentId == null) {
            // The provider sees a holding the registry never learned — quarantine posture:
            // report it, never invent an instrument for it.
            unregistered += observed
            continue
        }
        if (instrumentId to wallet in seen) continue
        deltas +=
            ReconDelta(
                chain = chain,
                wallet = wallet,
                instrumentId = instrumentId,
                mintAddress = mint,
                onchainRaw = observed.amountRaw,
                derivedRaw = BigInteger.ZERO,
                unclassifiedRaw = BigInteger.ZERO,
                deltaRaw = observed.amountRaw,
                decimals = observed.decimals,
                asOf = observed.asOf,
            )
    }
    return ReconReport(deltas, unregistered)
}
