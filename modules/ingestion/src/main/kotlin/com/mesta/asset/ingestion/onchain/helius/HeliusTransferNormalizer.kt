package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode
import com.mesta.asset.ingestion.onchain.CHAIN_SOLANA
import com.mesta.asset.ingestion.onchain.OnchainTransfer
import com.mesta.asset.ingestion.onchain.TransferDirection
import com.mesta.asset.ingestion.onchain.TransferKind
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
 * Deterministic identity: `externalId = "solana:<sig>:<account>:<leg>"` where leg is
 * `bal:<accountIndex>` for native SOL or `tok:<accountIndex>` for SPL — the same transaction
 * normalized by poller or webhook produces the same ids.
 */
class HeliusTransferNormalizer {
    fun normalize(
        tx: JsonNode,
        wallet: String,
    ): List<OnchainTransfer> {
        val meta = tx.path("meta")
        if (!meta.path("err").isNull) return emptyList()
        val transaction = tx.path("transaction")
        val keys = transaction.path("message").path("accountKeys")
        if (!keys.isArray) return emptyList()

        val signature = transaction.path("signatures").path(0).asText()
        val blockTime = Instant.ofEpochSecond(tx.path("blockTime").asLong())
        val blockHash =
            tx.path("blockHash").asText().ifEmpty { null }
                ?: transaction
                    .path("message")
                    .path("recentBlockhash")
                    .asText()
                    .ifEmpty { null }
        val slot = tx.path("slot").asLong()

        val legs = mutableListOf<OnchainTransfer>()

        // Native SOL: diff every lamport balance; only the watched wallet's legs are facts for us.
        val pre = meta.path("preBalances")
        val post = meta.path("postBalances")
        for (i in 0 until maxOf(pre.size(), post.size())) {
            val account = accountAt(keys, i)
            if (account != wallet) continue
            val delta = lamports(post, i) - lamports(pre, i)
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
            val delta = (after?.amountRaw() ?: BigInteger.ZERO) - (before?.amountRaw() ?: BigInteger.ZERO)
            if (delta == BigInteger.ZERO) continue
            val accountIndex = entry.path("accountIndex").asInt()
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
                    mintAddress = entry.path("mint").asText(),
                    amountRaw = delta.abs(),
                    decimals = entry.decimals(),
                    direction = if (delta.signum() > 0) TransferDirection.IN else TransferDirection.OUT,
                    transferKind = if (delta.signum() > 0) TransferKind.TRANSFER_IN else TransferKind.TRANSFER_OUT,
                )
        }
        return legs
    }

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

    private fun lamports(
        balances: JsonNode,
        index: Int,
    ): BigInteger = balances.path(index).takeIf { it.isNumber }?.bigIntegerValue() ?: BigInteger.ZERO

    private fun JsonNode.amountRaw(): BigInteger =
        path("uiTokenAmount")
            .path("amount")
            .takeIf { it.isTextual }
            ?.let { BigInteger(it.asText()) }
            ?: BigInteger.ZERO

    private fun JsonNode.decimals(): Int = path("uiTokenAmount").path("decimals").asInt()

    companion object {
        const val SOL_DECIMALS = 9
    }
}
