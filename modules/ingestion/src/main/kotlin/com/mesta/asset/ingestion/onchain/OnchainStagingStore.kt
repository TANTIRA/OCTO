package com.mesta.asset.ingestion.onchain

import java.util.UUID

/**
 * The persistence seam between the sync service and `mesta` staging. Implemented by JDBC in
 * `onchain/persistence`; faked in unit tests.
 */
interface OnchainStagingStore {
    /** Addresses whose latest `tracked_address_event` is `watched` on [chain]. */
    fun activeWatchedAddresses(chain: String): List<WatchSource>

    /**
     * The signature at the highest staged slot for this wallet — the incremental-sync `until`
     * cursor. Derived, never stored: replaying history can only strengthen it.
     */
    fun newestSignature(
        chain: String,
        wallet: String,
    ): String?

    /**
     * Batch-insert staging rows. Returns rows actually inserted — replays and webhook/poller
     * duplicates hit the unique (source_system, external_id) key and count as zero.
     */
    fun insertTransfers(
        transfers: List<OnchainTransfer>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int

    /**
     * Batch-insert observed-balance rows into `onchain_balance_snapshot`. `external_id` is
     * derived from the observation's identity — an identical observation from two delivery
     * routes dedupes on the unique key; a different amount is a different observation.
     */
    fun insertSnapshots(
        balances: List<OnchainBalance>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int
}
