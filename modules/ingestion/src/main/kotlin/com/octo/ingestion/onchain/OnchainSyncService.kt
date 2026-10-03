package com.octo.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.octo.ingestion.onchain.helius.HeliusException
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
 * Cursor: [SolanaHistoryCursor], not the highest staged slot (#509). A run walks newest-first
 * and stops after [maxPages] while Helius still has a pagination token; the next run continues
 * with that token, so a busy wallet's older transactions are fetched on a later pass instead
 * of being skipped when the tip is staged. Webhook deliveries write staging rows and do not
 * move this cursor, so a newly watched wallet still backfills. Transfers are staged before the
 * cursor moves: a crash replays the same pages and the unique key absorbs the overlap.
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
        val saved = store.historyCursor(watch.chain, watch.address)
        val floor = saved?.floorSlot
        var pageToken = saved?.resumeToken
        var pendingTip = saved?.pendingTipSlot

        var signaturesSeen = 0
        var transactionsFetched = 0
        val legs = mutableListOf<OnchainTransfer>()
        val skipped = mutableListOf<String>()
        val observedAt = Instant.now()

        var pages = 0
        var exhausted = false
        while (pages < maxPages) {
            val page = rpc.transactionsForAddress(watch.address, pageLimit, pageToken, floor)
            val data = page.path("data")
            if (!data.isArray || data.isEmpty) {
                exhausted = true
                break
            }
            // The first page of a new walk is the newest. Later pages, and a resumed walk,
            // are older — they must not replace the tip the floor will advance to.
            val captureTip = pageToken == null
            for (tx in data) {
                signaturesSeen++
                transactionsFetched++
                if (captureTip) pendingTip = laterSlot(pendingTip, tx)
                val parsed = normalizer.normalize(tx, watch.address, observedAt)
                legs += parsed.legs
                skipped += parsed.skipped
            }
            val next =
                page
                    .path("paginationToken")
                    .takeIf { it.isTextual }
                    ?.asText()
                    ?.takeIf { it.isNotEmpty() }
            if (next == null) {
                exhausted = true
                break
            }
            if (next == pageToken) {
                throw HeliusException("helius history pagination did not advance for ${watch.address}")
            }
            pageToken = next
            pages++
        }

        val staged = store.insertTransfers(legs, runId, correlationId, actor)
        val done = exhausted || pageToken == null
        store.saveHistoryCursor(
            watch.chain,
            watch.address,
            if (done) {
                SolanaHistoryCursor(floorSlot = pendingTip ?: floor, resumeToken = null, pendingTipSlot = null)
            } else {
                SolanaHistoryCursor(floorSlot = floor, resumeToken = pageToken, pendingTipSlot = pendingTip)
            },
        )
        return SyncResult(watch.address, signaturesSeen, transactionsFetched, staged, skipped)
    }

    /** Newest numeric slot on the opening page, ignoring a payload that omitted `slot`. */
    private fun laterSlot(
        current: Long?,
        tx: JsonNode,
    ): Long? {
        val slot = tx.path("slot").takeIf { it.isIntegralNumber }?.asLong() ?: return current
        return if (current == null || slot > current) slot else current
    }
}
