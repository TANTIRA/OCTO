package com.octo.ingestion.onchain

import java.util.UUID

/**
 * The persistence seam between the sync service and `octo` staging. Implemented by JDBC in
 * `onchain/persistence`; faked in unit tests.
 */
interface OnchainStagingStore {
    /** Addresses whose latest `tracked_address_event` is `watched` on [chain]. */
    fun activeWatchedAddresses(chain: String): List<WatchSource>

    /**
     * The highest staged slot for this wallet — the incremental-sync `filters.slot.gt` cursor.
     * Derived, never stored: replaying history can only strengthen it.
     */
    fun newestSlot(
        chain: String,
        wallet: String,
    ): Long?

    /**
     * The highest staged slot on [chain]. The EVM poller uses this only when
     * [scannedThrough] is null. A finished range with no transfers leaves it unchanged.
     */
    fun newestStagedSlot(chain: String): Long?

    /**
     * The last block the EVM poller finished scanning on [chain], inclusive, or null when
     * no checkpoint is stored. Quiet ranges advance this; [newestStagedSlot] does not.
     */
    fun scannedThrough(chain: String): Long?

    /**
     * Record that [chain] has been fully scanned through [block], inclusive. Monotonic: a
     * block at or below the stored checkpoint leaves the row where it is.
     */
    fun recordScannedThrough(
        chain: String,
        block: Long,
    )

    /**
     * Registered non-native instruments on [chain] — contract address + decimals. The EVM
     * balance collector and the decimals resolver read this instead of a vendor token list.
     */
    fun tokenContracts(chain: String): List<TokenContract>

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

    /** The latest non-superseded observation per (wallet, mint) — what recon diffs against. */
    fun latestSnapshots(
        chain: String,
        wallet: String,
    ): List<OnchainBalance>

    /**
     * Batch-insert claim-evidence rows into `onchain_claim_evidence`. The external id is the
     * query's identity — identical re-observations dedupe on the unique key.
     */
    fun insertEvidence(
        evidence: List<OnchainEvidence>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int
}
