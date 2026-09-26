package com.mesta.asset.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import com.mesta.asset.ingestion.onchain.OnchainTransfer
import com.mesta.asset.ingestion.onchain.TransferDirection
import com.mesta.asset.ingestion.onchain.TransferKind
import java.math.BigInteger
import java.time.Instant

/**
 * One ERC-20 `Transfer` log -> normalized [OnchainTransfer] legs, one per watched wallet
 * involved — a transfer between two watched wallets produces an `out` leg and an `in` leg.
 *
 * Facts come straight off the log's topics and data word; [decimals] is resolved by the
 * caller (registry -> `decimals()` call) because the log itself does not carry it.
 *
 * Deterministic identity: `externalId = "<chain>:<txHash>:<wallet>:log:<logIndex>"`, so a
 * poller re-scan of the same finalized range produces the same ids and dedupes on the
 * staging unique key.
 *
 * Skipped by construction:
 * - `removed` logs (defensive — the tag is finalized-only, but a removed flag means it
 *   never was a fact);
 * - zero-value transfers, spam-farmed noise — the same skip as Solana's zero-delta legs;
 * - `from == to` self-transfers: a wallet sending to itself nets zero, which is exactly
 *   the leg Solana's zero-delta rule drops — and `instrument_flow` has no honest `self`
 *   flow_type (inbound has three values, not four).
 * - mint/burn edges classify by the zero address: `from == 0x0` -> `mint` (inbound),
 *   `to == 0x0` -> `burn` (outbound). A bare log cannot distinguish airdrop/unlock/
 *   vesting-claim — those land as ordinary transfers, an honest degradation.
 */
class EvmTransferNormalizer {
    fun normalize(
        log: JsonNode,
        watched: Set<String>,
        decimals: Int,
        blockTime: Instant,
        chain: String,
        sourceSystem: String,
    ): List<OnchainTransfer> {
        if (log.path("removed").asBoolean()) return emptyList()
        val topics = log.path("topics")
        if (!topics.isArray || topics.size() < 3) return emptyList()
        val from = topicAddress(topics[1])
        val to = topicAddress(topics[2])
        if (from == to) return emptyList()

        val amount =
            runCatching { log.path("data").asText().toQuantity() }.getOrNull()
                ?: return emptyList()
        if (amount.signum() == 0) return emptyList()

        val txHash = log.path("transactionHash").asText()
        val logIndex = log.path("logIndex").asQuantity().toLong()
        val slot = log.path("blockNumber").asQuantity().toLong()
        val blockHash = log.path("blockHash").asText().ifEmpty { null }
        val contract = log.path("address").asText().lowercase()

        return buildList {
            for (wallet in setOf(from, to) - ZERO_ADDRESS) {
                if (wallet !in watched) continue
                val outbound = wallet == from
                add(
                    OnchainTransfer(
                        externalId = "$chain:$txHash:$wallet:log:$logIndex",
                        signature = txHash,
                        slot = slot,
                        blockHash = blockHash,
                        blockTime = blockTime,
                        wallet = wallet,
                        counterparty = if (outbound) to else from,
                        tokenAccount = null,
                        mintAddress = contract,
                        amountRaw = amount,
                        decimals = decimals,
                        direction = if (outbound) TransferDirection.OUT else TransferDirection.IN,
                        transferKind =
                            when {
                                from == ZERO_ADDRESS -> TransferKind.MINT
                                to == ZERO_ADDRESS -> TransferKind.BURN
                                outbound -> TransferKind.TRANSFER_OUT
                                else -> TransferKind.TRANSFER_IN
                            },
                        chain = chain,
                        sourceSystem = sourceSystem,
                    ),
                )
            }
        }
    }

    /** Topics carry the address as a 32-byte word; the last 20 bytes are the address. */
    private fun topicAddress(topic: JsonNode): String =
        "0x" +
            topic
                .asText()
                .removePrefix("0x")
                .takeLast(40)
                .lowercase()

    companion object {
        const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    }
}
