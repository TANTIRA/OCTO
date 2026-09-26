package com.mesta.asset.ingestion.onchain

import com.mesta.asset.ingestion.onchain.helius.HeliusRpcApi
import com.mesta.asset.ingestion.onchain.helius.HeliusTransferNormalizer
import java.util.UUID

/** Per-address outcome of one poll pass. */
data class SyncResult(
    val address: String,
    val signaturesSeen: Int,
    val transactionsFetched: Int,
    val transfersStaged: Int,
)

/**
 * The polling half of onchain ingestion: scan each watched address's full transaction history
 * newest-first via `getTransactionsForAddress` — the `tokenAccounts: "balanceChanged"` filter
 * makes Helius resolve the wallet's ATAs server-side, so a transfer that touches only a token
 * account (the wallet never appears in `accountKeys`) is still ingested and attributed to the
 * owner by the normalizer's `owner` field. Idempotent end to end — the staging unique key is
 * the only dedupe, so a re-run or a webhook overlapping the same signatures inserts nothing twice.
 *
 * Cursor: the highest staged slot, passed as `filters.slot.gt` — derived from staging, never
 * stored. A crashed pass restarts safely because staging rows are already facts.
 */
class OnchainSyncService(
    private val rpc: HeliusRpcApi,
    private val normalizer: HeliusTransferNormalizer,
    private val store: OnchainStagingStore,
    private val pageLimit: Int = 100,
    private val maxPages: Int = 20,
) {
    /** Poll every currently-watched address on [chain]. */
    fun syncAll(
        chain: String = CHAIN_SOLANA,
        actor: String = "helius-poller",
    ): List<SyncResult> = store.activeWatchedAddresses(chain).map { syncAddress(it, actor) }

    fun syncAddress(
        watch: WatchSource,
        actor: String = "helius-poller",
    ): SyncResult {
        val runId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        val newestSlot = store.newestSlot(watch.chain, watch.address)

        var pageToken: String? = null
        var signaturesSeen = 0
        var transactionsFetched = 0
        val legs = mutableListOf<OnchainTransfer>()

        var pages = 0
        while (pages < maxPages) {
            val page = rpc.transactionsForAddress(watch.address, pageLimit, pageToken, newestSlot)
            val data = page.path("data")
            if (!data.isArray || data.isEmpty) break
            for (tx in data) {
                signaturesSeen++
                transactionsFetched++
                legs += normalizer.normalize(tx, watch.address)
            }
            pageToken = page.path("paginationToken").takeIf { it.isTextual }?.asText()
            if (pageToken == null) break
            pages++
        }

        val staged = store.insertTransfers(legs, runId, correlationId, actor)
        return SyncResult(watch.address, signaturesSeen, transactionsFetched, staged)
    }
}
