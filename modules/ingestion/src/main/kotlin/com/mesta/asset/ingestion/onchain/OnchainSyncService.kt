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
 * The polling half of onchain ingestion: walk each watched address's signature history
 * newest-first, fetch + normalize each transaction, batch-stage the legs. Idempotent end to
 * end — the staging unique key is the only dedupe, so a re-run or a webhook overlapping the
 * same signatures inserts nothing twice.
 *
 * Cursor: the signature at the highest staged slot, passed as `until` — derived from staging,
 * never stored. A crashed pass restarts safely because staging rows are already facts.
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
        val until = store.newestSignature(watch.chain, watch.address)

        var before: String? = null
        var signaturesSeen = 0
        var transactionsFetched = 0
        val legs = mutableListOf<OnchainTransfer>()

        var pages = 0
        while (pages < maxPages) {
            val page = rpc.signaturesForAddress(watch.address, pageLimit, before, until)
            if (!page.isArray || page.isEmpty) break
            for (sigInfo in page) {
                signaturesSeen++
                val signature = sigInfo.path("signature").asText()
                val tx = rpc.transaction(signature) ?: continue
                transactionsFetched++
                legs += normalizer.normalize(tx, watch.address)
            }
            before = page.last().path("signature").asText()
            pages++
            if (page.size() < pageLimit) break
        }

        val staged = store.insertTransfers(legs, runId, correlationId, actor)
        return SyncResult(watch.address, signaturesSeen, transactionsFetched, staged)
    }
}
