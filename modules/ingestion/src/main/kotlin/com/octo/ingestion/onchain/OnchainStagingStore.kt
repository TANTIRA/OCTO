package com.octo.ingestion.onchain

import java.util.UUID

/**
 * Resume point for one Solana address's history walk (#509).
 *
 * Staged rows are the wrong watermark. The walk is newest-first and bounded per run, so the
 * highest staged slot is the tip of a partial page, and a webhook can stage that tip before
 * any backfill. Either one makes `filters.slot.gt` skip the older gap. This value is the
 * walk's own mark: [floorSlot] is the last slot a finished walk covered, [resumeToken] continues
 * a walk that stopped early, and [pendingTipSlot] is the newest slot of that open walk.
 */
data class SolanaHistoryCursor(
    val floorSlot: Long?,
    val resumeToken: String?,
    val pendingTipSlot: Long?,
)

/**
 * The persistence seam between the sync service and `octo` staging. Implemented by JDBC in
 * `onchain/persistence`; faked in unit tests.
 */
interface OnchainStagingStore {
    /** Addresses whose latest `tracked_address_event` is `watched` on [chain]. */
    fun activeWatchedAddresses(chain: String): List<WatchSource>

    /**
     * The highest staged slot for this wallet, staking rewards excluded. Not the Solana
     * history cursor — that is [historyCursor]. A webhook row or a truncated page would
     * otherwise move `filters.slot.gt` past transactions the walk has not seen (#509).
     */
    fun newestSlot(
        chain: String,
        wallet: String,
    ): Long?

    /**
     * The highest staged slot on [chain] — the EVM scanner's resume cursor. Derived from
     * staging like [newestSlot]: a crashed window re-scans idempotently because the
     * unique (source_system, external_id) key refuses duplicates.
     */
    fun newestStagedSlot(chain: String): Long?

    /**
     * The Solana history walk's resume point, or null when [wallet] has never been polled.
     * Default returns null so fakes that do not drive the poller stay source-compatible.
     */
    fun historyCursor(
        chain: String,
        wallet: String,
    ): SolanaHistoryCursor? = null

    /**
     * Persists [cursor] for the next poll. Default discards it; the JDBC store upserts
     * `octo.solana_history_cursor`. Called only after the page's transfers are staged, so a
     * crash retries the same pages and the unique key absorbs the overlap.
     */
    fun saveHistoryCursor(
        chain: String,
        wallet: String,
        cursor: SolanaHistoryCursor,
    ) = Unit

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
