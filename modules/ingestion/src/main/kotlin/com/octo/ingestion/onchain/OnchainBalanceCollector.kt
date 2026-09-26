package com.mesta.asset.ingestion.onchain

import com.mesta.asset.ingestion.onchain.helius.HeliusBalanceNormalizer
import com.mesta.asset.ingestion.onchain.helius.HeliusException
import com.mesta.asset.ingestion.onchain.helius.HeliusRpcApi
import com.mesta.asset.ingestion.onchain.helius.HeliusWalletApi
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
            val balances = collectWallet(watch.address, asOf, fallbacks, skipped, failed) ?: continue
            snapshots += staging.insertSnapshots(balances, ingestionRunId, UUID.randomUUID(), ACTOR)
        }
        return BalanceCollectionReport(wallets.size, snapshots, fallbacks, skipped, failed)
    }

    private fun collectWallet(
        address: String,
        asOf: Instant,
        fallbacks: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<String>,
    ): List<OnchainBalance>? {
        try {
            val all = mutableListOf<OnchainBalance>()
            var page = 1
            while (true) {
                val parsed = normalizer.fromWalletApi(walletApi.balances(address, page), address, asOf)
                all += parsed.balances
                skipped += parsed.skipped
                if (!parsed.hasMore) return all
                page++
            }
        } catch (e: HeliusException) {
            val client = rpc
            if (client == null) {
                failed += address
                return null
            }
            fallbacks += address
            return runCatching { normalizer.fromRpc(client.balance(address), client.tokenAccountsByOwner(address), address, asOf) }
                .onFailure { failed += address }
                .getOrNull()
        }
    }

    companion object {
        const val ACTOR = "helius-balance-collector"
    }
}
