package com.octo.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode
import com.octo.ingestion.onchain.BalanceSource
import com.octo.ingestion.onchain.OnchainBalance
import java.math.BigInteger
import java.time.Instant

/**
 * Helius balance payloads -> normalized [OnchainBalance] observations.
 *
 * `/v1/wallet/{addr}/balances` reports `balance` as a *UI-adjusted* decimal — there is no
 * `balanceRaw` field. Raw units are recovered as `balance × 10^decimals` via exact decimal
 * math; a row whose product is not an integer is dropped (returned in [BalanceParse.skipped])
 * rather than truncated into a wrong fact.
 *
 * The endpoint denotes native SOL by the wrapped-SOL mint `So111…112` — a different instrument
 * per the fit assessment — so it normalizes to `mintAddress = null`.
 */
class HeliusBalanceNormalizer {
    fun fromWalletApi(
        json: JsonNode,
        wallet: String,
        asOf: Instant,
    ): BalanceParse {
        val balances = mutableListOf<OnchainBalance>()
        val skipped = mutableListOf<String>()
        for (token in json.path("balances")) {
            val mint = token.path("mint").asText()
            // A missing or null `balance`/`decimals` would read as 0 through Jackson's defaults —
            // a wrong fact, not an empty holding — so anything but a number skips the row.
            val balance = token.path("balance")
            val decimals = token.path("decimals").takeIf { it.isInt }?.intValue()
            val raw =
                if (!balance.isNumber || decimals == null) {
                    null
                } else {
                    runCatching { balance.decimalValue().movePointRight(decimals).toBigIntegerExact() }.getOrNull()
                }
            if (raw == null || decimals == null) {
                skipped += mint
                continue
            }
            balances +=
                OnchainBalance(
                    wallet = wallet,
                    tokenAccount = null,
                    mintAddress = mint.takeIf { it != WRAPPED_SOL_MINT },
                    amountRaw = raw,
                    decimals = decimals,
                    usdValue = token.path("usdValue").takeIf { it.isNumber }?.doubleValue(),
                    source = BalanceSource.WALLET_API,
                    slot = null,
                    asOf = asOf,
                )
        }
        return BalanceParse(
            balances = balances,
            skipped = skipped,
            hasMore = json.path("pagination").path("hasMore").asBoolean(),
        )
    }

    /**
     * RPC fallback: `getBalance` lamports + `getTokenAccountsByOwner` grouped per mint.
     * An account whose amount or decimals cannot be parsed marks its mint
     * [RpcBalanceParse.unreadableMints] — like a wallet-API skip, the emptied-holdings
     * pass leaves that mint alone. An account whose mint itself is unreadable cannot be
     * attributed at all; those are counted in [RpcBalanceParse.unidentifiedAccounts] so the
     * caller can suppress every emptied-holding write for the wallet (#550).
     */
    fun fromRpc(
        lamports: Long,
        tokenAccounts: JsonNode,
        wallet: String,
        asOf: Instant,
    ): RpcBalanceParse {
        val balances = mutableListOf<OnchainBalance>()
        if (lamports > 0) {
            balances +=
                OnchainBalance(
                    wallet = wallet,
                    tokenAccount = null,
                    mintAddress = null,
                    amountRaw = BigInteger.valueOf(lamports),
                    decimals = SOL_DECIMALS,
                    usdValue = null,
                    source = BalanceSource.RPC,
                    slot = null,
                    asOf = asOf,
                )
        }
        val perMint = linkedMapOf<String, Pair<BigInteger, Int>>()
        val unreadableMints = mutableListOf<String>()
        var unidentified = 0
        for (account in tokenAccounts.path("value")) {
            val info =
                account
                    .path("account")
                    .path("data")
                    .path("parsed")
                    .path("info")
            val mint = info.path("mint").asText()
            if (mint.isEmpty()) {
                unidentified++
                continue
            }
            val amount = info.path("tokenAmount").path("amount")
            val decimals = info.path("tokenAmount").path("decimals")
            val raw = if (amount.isTextual) amount.asText().toBigIntegerOrNull() else null
            if (raw == null || !decimals.isInt) {
                unreadableMints += mint
                continue
            }
            if (raw.signum() <= 0) continue
            perMint.merge(mint, raw to decimals.asInt()) { (a, d), (b, _) -> a + b to d }
        }
        for ((mint, pair) in perMint) {
            balances +=
                OnchainBalance(
                    wallet = wallet,
                    tokenAccount = null,
                    mintAddress = mint,
                    amountRaw = pair.first,
                    decimals = pair.second,
                    usdValue = null,
                    source = BalanceSource.RPC,
                    slot = null,
                    asOf = asOf,
                )
        }
        return RpcBalanceParse(balances, unreadableMints, unidentified)
    }

    data class BalanceParse(
        val balances: List<OnchainBalance>,
        val skipped: List<String>,
        val hasMore: Boolean,
    )

    /** RPC-path result: parsed balances plus what could not be read (#550). */
    data class RpcBalanceParse(
        val balances: List<OnchainBalance>,
        val unreadableMints: List<String>,
        val unidentifiedAccounts: Int,
    )

    companion object {
        const val SOL_DECIMALS = 9

        /** The wrapped-SOL mint — the wallet API's stand-in for the native balance row. */
        const val WRAPPED_SOL_MINT = "So11111111111111111111111111111111111111112"
    }
}
