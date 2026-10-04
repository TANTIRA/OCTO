package com.octo.ingestion.onchain

import com.octo.ingestion.onchain.helius.HeliusBalanceNormalizer
import com.octo.ingestion.onchain.helius.HeliusException
import com.octo.ingestion.onchain.helius.HeliusRpcApi
import com.octo.ingestion.onchain.helius.HeliusWalletApi
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** What one `collect` pass did, for logs and the operator runbook. */
data class BalanceCollectionReport(
    val walletsSeen: Int,
    val snapshotsInserted: Int,
    val rpcFallbacks: List<String>,
    val skippedTokens: List<String>,
    val failedWallets: List<String>,
)

/**
 * Polls every watched wallet's holdings into `onchain_balance_snapshot` staging. The Wallet
 * API is the primary source; when it errors and an [HeliusRpcApi] is wired the collector falls
 * back to `getBalance` + `getTokenAccountsByOwner` (`source='rpc'` on the rows). A wallet that
 * fails both paths lands in [BalanceCollectionReport.failedWallets] and never blocks the rest
 * of the run — recon will flag its stale snapshot on the next report.
 */
class OnchainBalanceCollector(
    private val staging: OnchainStagingStore,
    private val walletApi: HeliusWalletApi,
    private val rpc: HeliusRpcApi?,
    private val normalizer: HeliusBalanceNormalizer = HeliusBalanceNormalizer(),
) {
    fun collect(
        chain: String = CHAIN_SOLANA,
        asOf: Instant = Instant.now(),
        ingestionRunId: UUID = UUID.randomUUID(),
    ): BalanceCollectionReport {
        var snapshots = 0
        val fallbacks = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val wallets = staging.activeWatchedAddresses(chain)
        for (watch in wallets) {
            val skippedBefore = skipped.size
            val fallbacksBefore = fallbacks.size
            val read = collectWallet(watch.address, asOf, fallbacks, skipped, failed) ?: continue
            val source = if (fallbacks.size > fallbacksBefore) BalanceSource.RPC else BalanceSource.WALLET_API
            val freshSkips = skipped.subList(skippedBefore, skipped.size)
            // Wallet API uses the wrapped-SOL mint as its native row. RPC skipped mints are real
            // SPL mints (wrapped SOL included) and must not be rewritten to the native key.
            val unreadable = mutableSetOf<String?>()
            if (source == BalanceSource.RPC) unreadable += freshSkips else unreadable += freshSkips.map(::mintOf)
            val emptied =
                emptiedHoldings(
                    chain,
                    watch.address,
                    read.balances,
                    unreadable,
                    source,
                    asOf,
                    read.unidentifiedTokenAccounts,
                )
            snapshots += staging.insertSnapshots(read.balances + emptied, ingestionRunId, UUID.randomUUID(), ACTOR)
        }
        return BalanceCollectionReport(wallets.size, snapshots, fallbacks, skipped, failed)
    }

    private fun collectWallet(
        address: String,
        asOf: Instant,
        fallbacks: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<String>,
    ): WalletRead? {
        try {
            val all = mutableListOf<OnchainBalance>()
            var page = 1
            while (true) {
                val parsed = normalizer.fromWalletApi(walletApi.balances(address, page), address, asOf)
                all += parsed.balances
                skipped += parsed.skipped
                if (!parsed.hasMore) return WalletRead(all)
                page++
            }
        } catch (e: HeliusException) {
            val client = rpc
            if (client == null) {
                failed += address
                return null
            }
            fallbacks += address
            return runCatching {
                val parsed = normalizer.fromRpc(client.balance(address), client.tokenAccountsByOwner(address), address, asOf)
                skipped += parsed.skipped
                WalletRead(parsed.balances, parsed.unidentified)
            }.onFailure { failed += address }
                .getOrNull()
        }
    }

    // Both sources omit empty holdings, so the table stays sparse — except where the latest
    // snapshot still shows a holding: there a zero must land, or recon keeps reading the stale
    // amount. Stake rows (token_account set) belong to the staking collector, and a mint this
    // pass could not read is left alone rather than zeroed. An RPC account with no readable
    // mint might be any prior token holding, so none of those unread mints are zeroed either.
    // Native SOL still comes from getBalance and can be zeroed on its own.
    private fun emptiedHoldings(
        chain: String,
        wallet: String,
        observed: List<OnchainBalance>,
        unreadable: Set<String?>,
        source: BalanceSource,
        asOf: Instant,
        unidentifiedTokenAccounts: Boolean,
    ): List<OnchainBalance> {
        val seen = observed.map { it.mintAddress }.toSet()
        return staging
            .latestSnapshots(chain, wallet)
            .filter { it.tokenAccount == null && it.amountRaw.signum() > 0 }
            .filter {
                it.mintAddress !in seen &&
                    it.mintAddress !in unreadable &&
                    !(unidentifiedTokenAccounts && it.mintAddress != null)
            }.distinctBy { it.mintAddress }
            .map { it.copy(amountRaw = BigInteger.ZERO, usdValue = null, source = source, slot = null, asOf = asOf) }
    }

    private data class WalletRead(
        val balances: List<OnchainBalance>,
        val unidentifiedTokenAccounts: Boolean = false,
    )

    private fun mintOf(walletApiMint: String): String? = walletApiMint.takeIf { it != HeliusBalanceNormalizer.WRAPPED_SOL_MINT }

    companion object {
        const val ACTOR = "helius-balance-collector"
    }
}
