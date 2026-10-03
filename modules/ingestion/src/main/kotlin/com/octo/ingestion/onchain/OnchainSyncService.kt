package com.octo.ingestion.onchain

import com.octo.ingestion.onchain.helius.HeliusRpcApi
import com.octo.ingestion.onchain.helius.HeliusTransferNormalizer
import java.time.Instant
import java.util.UUID

/** Per-address outcome of one poll pass. */
data class SyncResult(
    val address: String,
    val signaturesSeen: Int,
    val transactionsFetched: Int,
    val transfersStaged: Int,
    val skippedLegs: List<String> = emptyList(),
)

/**
 * The polling half of onchain ingestion: scan each watched address's full transaction history
 * newest-first via `getTransactionsForAddress` — the `tokenAccounts: "balanceChanged"` filter
 * makes Helius resolve the wallet's ATAs server-side, so a transfer that touches only a token
 * account (the wallet never appears in `accountKeys`) is still ingested and attributed to the
 * owner by the normalizer's `owner` field. Idempotent end to end — the staging unique key is
 * the only dedupe, so a re-run or a webhook overlapping the same signatures inserts nothing twice.
 *
 * Cursor: the highest slot the poller itself staged, passed as `filters.slot.gt` — derived
 * from staging, scoped to the poller's actor so webhook-staged rows cannot move it (#509).
 * A crashed pass restarts safely because staging rows are already facts.
 *
 * When the gap since that cursor is deeper than one pass's page budget, the descent is
 * stored as a [SyncFrontier] (V48's `onchain_sync_frontier`): the next pass resumes it with
 * `slot.lte = ceiling` instead of restarting at the top, so the gap's low end is fetched
 * rather than skipped forever. While a frontier is open it takes the whole pass — the gap
 * closes before the top edge advances again.
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
        val frontier = store.syncFrontier(watch.chain, watch.address)
        val newestSlot = store.newestSlot(watch.chain, watch.address, actor)

        // Resuming a truncated descent outranks the incremental edge (#509): the open
        // frontier supplies both bounds — floor (null: backfill to genesis) and ceiling —
        // so the walk picks up below the lowest slot it already fetched.
        val slotGt = frontier?.floorSlot ?: newestSlot
        val slotLte = frontier?.ceilingSlot

        var pageToken: String? = null
        var signaturesSeen = 0
        var transactionsFetched = 0
        var minSlot: Long? = null
        val legs = mutableListOf<OnchainTransfer>()
        val skipped = mutableListOf<String>()
        val observedAt = Instant.now()

        var pages = 0
        while (pages < maxPages) {
            val page = rpc.transactionsForAddress(watch.address, pageLimit, pageToken, slotGt, slotLte)
            val data = page.path("data")
            if (!data.isArray || data.isEmpty) {
                pageToken = null
                break
            }
            for (tx in data) {
                signaturesSeen++
                transactionsFetched++
                minSlot = minOf(minSlot ?: Long.MAX_VALUE, tx.path("slot").asLong())
                val parsed = normalizer.normalize(tx, watch.address, observedAt)
                legs += parsed.legs
                skipped += parsed.skipped
            }
            pageToken = page.path("paginationToken").takeIf { it.isTextual }?.asText()
            if (pageToken == null) break
            pages++
        }

        val staged = store.insertTransfers(legs, runId, correlationId, actor)
        when {
            // The budget ran out with pages left: record where the descent stopped (#509).
            pageToken != null ->
                checkNotNull(minSlot).let { ceiling ->
                    store.saveSyncFrontier(SyncFrontier(watch.chain, watch.address, slotGt, ceiling))
                }
            // A resumed walk just exhausted its range — the gap is closed.
            frontier != null -> store.clearSyncFrontier(watch.chain, watch.address)
            else -> Unit
        }
        return SyncResult(watch.address, signaturesSeen, transactionsFetched, staged, skipped)
    }
}
