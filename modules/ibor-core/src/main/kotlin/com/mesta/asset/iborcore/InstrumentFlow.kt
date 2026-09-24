package com.mesta.asset.iborcore

import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/**
 * Mirrors the `instrument-flow-type` @values in `ontology/mesta-investment.tql` and the check
 * constraints on `mesta.onchain_transfer.transfer_kind` / `mesta.instrument_flow.flow_type` —
 * one string travels staging -> token ledger -> TypeDB and is never re-translated.
 *
 * [inbound] is the sign the flow contributes to a wallet's token position: true credits,
 * false debits, null for `other` — a fact we keep but cannot sign without human review, so it
 * lands in [TokenPosition.unclassifiedRaw] instead of being silently netted.
 */
enum class InstrumentFlowType(
    val wireValue: String,
    val inbound: Boolean?,
) {
    TRANSFER_IN("transfer-in", true),
    TRANSFER_OUT("transfer-out", false),
    STAKING_REWARD("staking-reward", true),
    AIRDROP("airdrop", true),
    UNLOCK("unlock", true),
    VESTING_CLAIM("vesting-claim", true),
    MINT("mint", true),
    BURN("burn", false),
    OTHER("other", null),
}

/**
 * One row of `mesta.instrument_flow` — the onchain counterpart of [LedgerEvent]. [amountRaw] is
 * a non-negative magnitude in base units (like `ledger_event.monetary_amount`); direction lives
 * in [flowType], never in a sign bit. [decimals] is what the source reported at observation time.
 */
data class InstrumentFlow(
    val id: UUID,
    val externalId: String,
    val instrumentId: UUID,
    val wallet: String,
    val tokenAccount: String?,
    val flowType: InstrumentFlowType,
    val amountRaw: BigInteger,
    val decimals: Int,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val slot: Long?,
    val signature: String?,
    val supersedesId: UUID? = null,
)

/**
 * The flow table as it was known at [knownAt]: rows recorded later are ignored and every row a
 * remaining row supersedes is dropped, so correction chains resolve to their last link. Same
 * rules as [currentEvents].
 */
fun currentFlows(
    flows: List<InstrumentFlow>,
    knownAt: Instant,
): List<InstrumentFlow> = resolveCurrent(flows, knownAt, InstrumentFlow::id, InstrumentFlow::supersedesId, InstrumentFlow::recordedAt)

/**
 * Derived holding of one (instrument, wallet) pair. Raw base units only — dividing by
 * 10^[decimals] is display work. [netRaw] sums only directional flows; `other` rows surface in
 * [unclassifiedRaw] so an unresolved fact can never masquerade as a zero position.
 */
data class TokenPosition(
    val instrumentId: UUID,
    val wallet: String,
    val decimals: Int,
    val netRaw: BigInteger,
    val inboundRaw: BigInteger,
    val outboundRaw: BigInteger,
    val unclassifiedRaw: BigInteger,
)

/**
 * Derives one position per (instrument, wallet) pair present in [flows], after supersession is
 * resolved at [knownAt]. A wallet's balance never exists as stored state — this function is the
 * only way it is known (ADR-0001 §3).
 */
fun tokenPositions(
    flows: List<InstrumentFlow>,
    knownAt: Instant,
): List<TokenPosition> =
    currentFlows(flows, knownAt)
        .groupBy { it.instrumentId to it.wallet }
        .map { (key, members) ->
            val decimals = members.map { it.decimals }.distinct().singleOrNull()
            requireNotNull(decimals) { "instrument ${key.first} observed with mixed decimals" }
            TokenPosition(
                instrumentId = key.first,
                wallet = key.second,
                decimals = decimals,
                netRaw = members.sumOf { it.signed() },
                inboundRaw = members.filter { it.flowType.inbound == true }.sumOf { it.amountRaw },
                outboundRaw = members.filter { it.flowType.inbound == false }.sumOf { it.amountRaw },
                unclassifiedRaw = members.filter { it.flowType.inbound == null }.sumOf { it.amountRaw },
            )
        }

private fun InstrumentFlow.signed(): BigInteger =
    when (flowType.inbound) {
        true -> amountRaw
        false -> amountRaw.negate()
        null -> BigInteger.ZERO
    }
