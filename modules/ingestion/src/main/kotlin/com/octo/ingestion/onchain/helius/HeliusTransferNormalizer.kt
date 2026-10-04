package com.octo.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode
import com.octo.ingestion.onchain.CHAIN_SOLANA
import com.octo.ingestion.onchain.OnchainTransfer
import com.octo.ingestion.onchain.TransferDirection
import com.octo.ingestion.onchain.TransferKind
import java.math.BigInteger
import java.time.Instant

/**
 * `getTransaction` (jsonParsed) -> normalized [OnchainTransfer] legs for one watched wallet.
 *
 * Facts come from `meta.preBalances`/`postBalances` and `meta.preTokenBalances`/
 * `postTokenBalances` — the balance *diffs* the chain itself reports — rather than decoded
 * instructions, so inner-program transfers are captured without per-program parsers. Failed
 * transactions (`meta.err != null`) yield nothing: their only movement is the fee leg, which is
 * dust the plan deliberately excludes.
 *
 * Fail-closed on malformed payloads: a leg whose amount, balance, or decimals cannot be parsed
 * is skipped and reported in [TransferParse.skipped] — never coerced to zero, which would
 * fabricate a full-balance transfer that never happened. A missing `blockTime` falls back to
 * [observedAt] (the convention `HeliusStakingNormalizer` established) instead of epoch 0.
 *
 * When the watched wallet pays the fee (`accountKeys[0]`), `meta.fee` is staged as its own
 * `direction = fee` leg and removed from the native SOL leg, so legs still net to the wallet's
 * lamport delta. An absent `fee` is treated as zero (the whole delta stays one leg); a present but
 * malformed one skips the native leg. A balance leg staged before this split already holds the fee
 * under the same `bal:0` id; staging drops the new fee leg in that case so a redelivery does not
 * debit it again.
 *
 * Deterministic identity: `externalId = "solana:<sig>:<account>:<leg>"` where leg is
 * `bal:<accountIndex>` for native SOL, `fee` for the network fee, or `tok:<accountIndex>` for
 * SPL — the same transaction normalized by poller or webhook produces the same ids.
 */
class HeliusTransferNormalizer {
    fun normalize(
        tx: JsonNode,
        wallet: String,
        observedAt: Instant,
    ): TransferParse {
        val meta = tx.path("meta")
        if (!meta.path("err").isNull) return TransferParse(emptyList(), emptyList())
        val transaction = tx.path("transaction")
        val keys = transaction.path("message").path("accountKeys")
        if (!keys.isArray) return TransferParse(emptyList(), emptyList())

        val signature = transaction.path("signatures").path(0).asText()
        val blockTime =
            tx
                .path("blockTime")
                .takeIf { it.isNumber }
                ?.let { Instant.ofEpochSecond(it.asLong()) }
                ?: observedAt
        val blockHash =
            tx.path("blockHash").asText().ifEmpty { null }
                ?: transaction
                    .path("message")
                    .path("recentBlockhash")
                    .asText()
                    .ifEmpty { null }
        val slot = tx.path("slot").asLong()

        val legs = mutableListOf<OnchainTransfer>()
        val skipped = mutableListOf<String>()

        // Native SOL: diff every lamport balance; only the watched wallet's legs are facts for us.
        val pre = meta.path("preBalances")
        val post = meta.path("postBalances")
        for (i in 0 until maxOf(pre.size(), post.size())) {
            val account = accountAt(keys, i)
            if (account != wallet) continue
            val after = lamports(post, i)
            val before = lamports(pre, i)
            if (after == null || before == null) {
                skipped += "bal:$i (non-numeric balance)"
                continue
            }
            // accountKeys[0] is the fee payer. Its lamport delta includes `meta.fee`; split that out
            // into its own leg so the network fee is never booked as part of a transfer.
            val fee =
                if (i == 0) {
                    val feeNode = meta.path("fee")
                    when {
                        feeNode.isMissingNode || feeNode.isNull -> BigInteger.ZERO
                        feeNode.isIntegralNumber && feeNode.bigIntegerValue().signum() >= 0 -> feeNode.bigIntegerValue()
                        else -> {
                            skipped += "bal:$i (malformed fee)"
                            continue
                        }
                    }
                } else {
                    BigInteger.ZERO
                }
            if (fee.signum() > 0) {
                legs +=
                    OnchainTransfer(
                        externalId = "$CHAIN_SOLANA:$signature:$account:fee",
                        signature = signature,
                        slot = slot,
                        blockHash = blockHash,
                        blockTime = blockTime,
                        wallet = wallet,
                        counterparty = null,
                        tokenAccount = null,
                        mintAddress = null,
                        amountRaw = fee,
                        decimals = SOL_DECIMALS,
                        direction = TransferDirection.FEE,
                        // There is no fee flow type; a fee leaves the wallet, so it debits the
                        // position exactly like any other outflow.
                        transferKind = TransferKind.TRANSFER_OUT,
                    )
            }
            val delta = after - before + fee
            if (delta == BigInteger.ZERO) continue
            legs +=
                OnchainTransfer(
                    externalId = "$CHAIN_SOLANA:$signature:$account:bal:$i",
                    signature = signature,
                    slot = slot,
                    blockHash = blockHash,
                    blockTime = blockTime,
                    wallet = wallet,
                    counterparty = null,
                    tokenAccount = null,
                    mintAddress = null,
                    amountRaw = delta.abs(),
                    decimals = SOL_DECIMALS,
                    direction = if (delta.signum() > 0) TransferDirection.IN else TransferDirection.OUT,
                    transferKind = if (delta.signum() > 0) TransferKind.TRANSFER_IN else TransferKind.TRANSFER_OUT,
                )
        }

        // SPL/token-2022: union of pre/post token balances keyed by accountIndex+mint.
        val preTok = tokenBalanceMap(meta.path("preTokenBalances"))
        val postTok = tokenBalanceMap(meta.path("postTokenBalances"))
        for (key in preTok.keys + postTok.keys) {
            val after = postTok[key]
            val before = preTok[key]
            val entry = after ?: before ?: continue
            val owner = entry.path("owner").asText().orEmpty()
            if (owner != wallet) continue
            val accountIndex = entry.path("accountIndex").asInt()
            val mint = entry.path("mint").asText()
            val afterAmt = after?.amountRawOrNull()
            val beforeAmt = before?.amountRawOrNull()
            // An absent side is a real zero (account created/closed); an unparseable present
            // side is a malformed payload — skip the leg rather than diff against a fake zero.
            if ((after != null && afterAmt == null) || (before != null && beforeAmt == null)) {
                skipped += "tok:$accountIndex:$mint (malformed amount)"
                continue
            }
            val delta = (afterAmt ?: BigInteger.ZERO) - (beforeAmt ?: BigInteger.ZERO)
            if (delta == BigInteger.ZERO) continue
            val decimals = entry.decimalsOrNull()
            if (decimals == null) {
                skipped += "tok:$accountIndex:$mint (missing decimals)"
                continue
            }
            val account = accountAt(keys, accountIndex)
            legs +=
                OnchainTransfer(
                    externalId = "$CHAIN_SOLANA:$signature:$account:tok:$accountIndex",
                    signature = signature,
                    slot = slot,
                    blockHash = blockHash,
                    blockTime = blockTime,
                    wallet = wallet,
                    counterparty = null,
                    tokenAccount = account,
                    mintAddress = mint,
                    amountRaw = delta.abs(),
                    decimals = decimals,
                    direction = if (delta.signum() > 0) TransferDirection.IN else TransferDirection.OUT,
                    transferKind = if (delta.signum() > 0) TransferKind.TRANSFER_IN else TransferKind.TRANSFER_OUT,
                )
        }
        return TransferParse(legs, skipped)
    }

    /** What one transaction normalized to: [legs] are facts, [skipped] are legs that could not be trusted. */
    data class TransferParse(
        val legs: List<OnchainTransfer>,
        val skipped: List<String>,
    )

    /** Account keys are either plain strings or {pubkey, signer, ...} objects in jsonParsed. */
    private fun accountAt(
        keys: JsonNode,
        index: Int,
    ): String =
        keys
            .path(index)
            .path("pubkey")
            .asText()
            .ifEmpty { keys.path(index).asText() }

    /** Keyed by (accountIndex, mint) so a wallet holding the same mint in two accounts stays distinct. */
    private fun tokenBalanceMap(balances: JsonNode): Map<String, JsonNode> =
        if (balances.isArray) {
            balances.associateBy { "${it.path("accountIndex").asInt()}:${it.path("mint").asText()}" }
        } else {
            emptyMap()
        }

    /** Null when the element is absent or non-numeric — a delta against it cannot be trusted. */
    private fun lamports(
        balances: JsonNode,
        index: Int,
    ): BigInteger? = balances.path(index).takeIf { it.isNumber }?.bigIntegerValue()

    /** Null when the amount is missing, non-textual, or unparseable — never coerced to zero. */
    private fun JsonNode.amountRawOrNull(): BigInteger? =
        path("uiTokenAmount")
            .path("amount")
            .takeIf { it.isTextual }
            ?.let { it.asText().toBigIntegerOrNull() }

    /** Null when decimals is absent or non-numeric — a wrong decimals silently misprices the leg. */
    private fun JsonNode.decimalsOrNull(): Int? =
        path("uiTokenAmount")
            .path("decimals")
            .takeIf { it.isInt }
            ?.asInt()

    companion object {
        const val SOL_DECIMALS = 9
    }
}
