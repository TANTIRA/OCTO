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
     * The highest slot [actor] staged for this wallet — the incremental-sync `filters.slot.gt`
     * cursor. Scoped to the caller's own writes (#509): rows a different pipeline staged (the
     * webhook's `helius-webhook` actor) must not move the poller's cursor, or a newly watched
     * wallet's history below a delivered transaction would be skipped forever.
     */
    fun newestSlot(
        chain: String,
        wallet: String,
        actor: String,
    ): Long?

    /**
     * The in-progress history descent for one watched address, or null when none is open
     * (#509). A frontier outranks the incremental cursor: until the gap closes, passes
     * continue the descent below [SyncFrontier.ceilingSlot].
     */
    fun syncFrontier(
        chain: String,
        wallet: String,
    ): SyncFrontier?

    /** Records where a truncated descent stopped; the next pass resumes below its ceiling. */
    fun saveSyncFrontier(frontier: SyncFrontier)

    /** Clears the descent — its walk reached the floor (or genesis); the gap is closed. */
    fun clearSyncFrontier(
        chain: String,
        wallet: String,
    )

    /**
     * The highest staged slot on [chain]. The EVM scanner reads this only to bootstrap a
     * deployment that staged rows before V47's [scanCheckpoint] existed — after that the
     * stored checkpoint is the resume cursor (#494).
     */
    fun newestStagedSlot(chain: String): Long?

    /**
     * The highest block the EVM scan finished on [chain], or null before the first pass
     * completes. Stored, never derived: block ranges with no watched transfers still advance
     * it, so a quiet period is never rescanned (#494).
     */
    fun scanCheckpoint(chain: String): Long?

    /**
     * Marks [block] as fully scanned on [chain]; the cursor never moves backward. Called
     * only after a window's legs are staged — a crash before the save re-scans the window
     * and the unique (source_system, external_id) key deduplicates it.
     */
    fun saveScanCheckpoint(
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
