package com.octo.iborcore

import com.octo.persistence.TenantScope
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** Writer identity recorded on every promoted row; the fact's origin stays in `source_system`. */
const val PROMOTION_ACTOR = "ibor-promotion"

/** How a staging row resolves to an instrument: the chain's native asset has no mint. */
data class InstrumentKey(
    val chain: String,
    val mintAddress: String?,
)

/**
 * The promoter's view of one `octo.onchain_transfer` row. Vendor-neutral by construction —
 * Helius types never leave `modules/ingestion`, and this module reads staging through JDBC only.
 */
data class StagedTransfer(
    val id: UUID,
    val externalId: String,
    val chain: String,
    val signature: String,
    val slot: Long,
    val blockTime: Instant,
    val wallet: String,
    val tokenAccount: String?,
    val mintAddress: String?,
    val amountRaw: BigInteger,
    val decimals: Int,
    val transferKind: String,
    val supersedesId: UUID?,
    val rationale: String?,
    val recordedAt: Instant,
    val sourceSystem: String,
    val ingestionRunId: UUID,
    val correlationId: UUID,
)

/**
 * One `(chain, mint)` whose staged transfers cannot promote — no registered instrument —
 * with the count of rows waiting on it (#495). Reported per pass without loading the rows.
 */
data class QuarantinedMint(
    val chain: String,
    val mintAddress: String?,
    val staged: Long,
)

/** What [InstrumentFlowPromoter.promote] did in one pass. */
data class PromotionReport(
    val promoted: Int,
    val quarantined: List<QuarantinedMint>,
    val deferred: List<StagedTransfer>,
)

/** Storage the promoter needs; the JDBC implementation lives in `persistence`. */
interface InstrumentFlowStore {
    /**
     * Up to [limit] finalized staging rows that can promote now — the `(chain, mint)` has a
     * registered instrument and no `instrument_flow` counterpart exists yet — in
     * `recorded_at` order. Rows waiting on an unregistered mint are excluded and surface
     * through [quarantinedMints] instead, so a backlog of spam airdrops never becomes the
     * pass's working set (#495). [limit] bounds the pass; the remainder promotes on later
     * ticks.
     */
    fun promotableTransfers(limit: Int): List<StagedTransfer>

    /**
     * Unpromotable staged rows grouped by their `(chain, mint)` — the quarantine summary a
     * pass reports without loading the rows themselves (#495).
     */
    fun quarantinedMints(): List<QuarantinedMint>

    /** Every registered instrument keyed by `(chain, mint)`; a null mint is the native asset. */
    fun instrumentIds(): Map<InstrumentKey, UUID>

    /** The flow promoted from a given staging row, if it exists yet. */
    fun flowIdForStaging(stagingRowId: UUID): UUID?

    /**
     * False when `(source_system, external_id)` was already promoted — replay is a no-op. The
     * staging row's lineage travels with the fact so it traces back to the observation run.
     */
    fun insertFlow(
        flow: InstrumentFlow,
        sourceSystem: String,
        ingestionRunId: UUID,
        correlationId: UUID,
    ): Boolean

    /**
     * Every flow for one wallet on one chain visible to [scope], `recorded_at` order — what
     * derivation consumes. A wallet another tenant tracks reads as no flows, never as a leak.
     */
    fun flowsFor(
        chain: String,
        wallet: String,
        scope: TenantScope,
    ): List<InstrumentFlow>
}

/**
 * Moves finalized staging facts into the token ledger. Three rules keep the ledger honest:
 * unknown mints are quarantined for review instead of silently becoming instruments, a
 * correction waits until the fact it supersedes exists (a dangling `supersedes_id` would break
 * `resolveCurrent`), and `external_id` identity makes any replay a no-op.
 *
 * A pass is bounded to [batchSize] rows (#495): quarantined mints are counted without being
 * loaded, so promotion cost tracks new work, not the growing history of unpromotable rows.
 */
class InstrumentFlowPromoter(
    private val store: InstrumentFlowStore,
    private val batchSize: Int = 500,
) {
    fun promote(now: Instant = Instant.now()): PromotionReport {
        val instruments = store.instrumentIds()
        val deferred = mutableListOf<StagedTransfer>()
        var promoted = 0
        for (row in store.promotableTransfers(batchSize)) {
            // promotableTransfers already filtered to registered mints; a null here means a
            // store that does not honor that contract — skip rather than invent an instrument.
            val instrumentId = instruments[InstrumentKey(row.chain, row.mintAddress)] ?: continue
            val supersedes = row.supersedesId?.let(store::flowIdForStaging)
            if (row.supersedesId != null && supersedes == null) {
                deferred += row
                continue
            }
            val flow =
                InstrumentFlow(
                    id = UUID.randomUUID(),
                    externalId = row.externalId,
                    instrumentId = instrumentId,
                    chain = row.chain,
                    wallet = row.wallet,
                    tokenAccount = row.tokenAccount,
                    flowType = InstrumentFlowType.entries.first { it.wireValue == row.transferKind },
                    amountRaw = row.amountRaw,
                    decimals = row.decimals,
                    occurredAt = row.blockTime,
                    recordedAt = now,
                    slot = row.slot,
                    signature = row.signature,
                    supersedesId = supersedes,
                    rationale = row.rationale,
                )
            if (store.insertFlow(flow, row.sourceSystem, row.ingestionRunId, row.correlationId)) promoted++
        }
        return PromotionReport(promoted, store.quarantinedMints(), deferred)
    }
}
