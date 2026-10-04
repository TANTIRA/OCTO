package com.octo.ingestion.onchain

import java.math.BigInteger
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
     * The highest staged slot on [chain]. The EVM poller uses this only when
     * [scannedThrough] is null. A finished range with no transfers leaves it unchanged.
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
     *
     * A Solana fee leg is not inserted when [omitSolanaFeesAlreadyBooked] finds that fee already
     * inside a legacy native-balance leg. Re-normalizing that older shape must not debit it twice.
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

/**
 * A native-balance leg already in `onchain_transfer`. Compared with a re-normalized batch to see
 * whether its amount still includes the network fee.
 */
internal data class StagedNativeLeg(
    val sourceSystem: String,
    val externalId: String,
    val amountRaw: BigInteger,
    val direction: TransferDirection,
)

/**
 * Drops Solana fee legs whose lamports are already inside a legacy balance leg.
 *
 * Before the fee was its own leg, the fee payer's native leg used
 * `solana:<sig>:<account>:bal:0` and stored `|post - pre|`, fee included. Re-normalization emits
 * that same id with the fee removed, plus `...:fee`. The balance id conflicts and is kept as-is;
 * the fee id is new and would debit the fee again. When the staged `bal:0` amount and direction
 * are exactly that legacy leg (`(post - pre) = newSignedDelta - fee`), the fee leg is omitted.
 * A staged fee-free amount is left alone so a missing fee row can still be inserted. Nothing here
 * writes position or cash state — those stay derived from the legs that remain.
 *
 * [stagedNativeLegs] must be the staged `bal:0` siblings of [incoming]'s fee legs, keyed by the
 * same `(source_system, external_id)` as the unique staging key. An empty collection means nothing
 * legacy is staged, so every incoming leg is kept.
 */
internal fun omitSolanaFeesAlreadyBooked(
    incoming: List<OnchainTransfer>,
    stagedNativeLegs: Collection<StagedNativeLeg>,
): List<OnchainTransfer> {
    if (incoming.none { it.isSolanaNetworkFee() } || stagedNativeLegs.isEmpty()) return incoming
    val staged = stagedNativeLegs.associateBy { it.sourceSystem to it.externalId }
    val incomingById = incoming.associateBy { it.sourceSystem to it.externalId }
    val drop = mutableSetOf<Pair<String, String>>()
    for (fee in incoming) {
        if (!fee.isSolanaNetworkFee()) continue
        val balanceId = legacyBalanceExternalId(fee.externalId) ?: continue
        val stagedBalance = staged[fee.sourceSystem to balanceId] ?: continue
        val newBalance = incomingById[fee.sourceSystem to balanceId]
        val newSigned =
            when (newBalance?.direction) {
                null -> BigInteger.ZERO
                TransferDirection.IN -> newBalance.amountRaw
                TransferDirection.OUT -> newBalance.amountRaw.negate()
                else -> continue
            }
        val legacySigned = newSigned - fee.amountRaw
        if (legacySigned.signum() == 0) continue
        val legacyDirection = if (legacySigned.signum() > 0) TransferDirection.IN else TransferDirection.OUT
        if (stagedBalance.amountRaw == legacySigned.abs() && stagedBalance.direction == legacyDirection) {
            drop += fee.sourceSystem to fee.externalId
        }
    }
    if (drop.isEmpty()) return incoming
    return incoming.filter { (it.sourceSystem to it.externalId) !in drop }
}

/** Fee payer is `accountKeys[0]`, so the legacy native leg for a `...:fee` id is `...:bal:0`. */
internal fun legacyBalanceExternalId(feeExternalId: String): String? {
    if (!feeExternalId.endsWith(":fee")) return null
    return feeExternalId.removeSuffix(":fee") + ":bal:0"
}

internal fun OnchainTransfer.isSolanaNetworkFee(): Boolean =
    chain == CHAIN_SOLANA && direction == TransferDirection.FEE && externalId.endsWith(":fee")
