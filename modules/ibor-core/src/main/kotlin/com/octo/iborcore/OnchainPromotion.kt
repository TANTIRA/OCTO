package com.mesta.asset.iborcore

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
 * The promoter's view of one `mesta.onchain_transfer` row. Vendor-neutral by construction —
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

/** What [InstrumentFlowPromoter.promote] did in one pass. */
data class PromotionReport(
    val promoted: Int,
    val quarantined: List<StagedTransfer>,
    val deferred: List<StagedTransfer>,
)

/** Storage the promoter needs; the JDBC implementation lives in `persistence`. */
interface InstrumentFlowStore {
    /** Staging rows with no `instrument_flow` counterpart yet, in `recorded_at` order. */
    fun unpromotedTransfers(): List<StagedTransfer>

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

    /** Every flow for one wallet on one chain, `recorded_at` order — what derivation consumes. */
    fun flowsFor(
        chain: String,
        wallet: String,
    ): List<InstrumentFlow>
}

/**
 * Moves finalized staging facts into the token ledger. Three rules keep the ledger honest:
 * unknown mints are quarantined for review instead of silently becoming instruments, a
 * correction waits until the fact it supersedes exists (a dangling `supersedes_id` would break
 * `resolveCurrent`), and `external_id` identity makes any replay a no-op.
 */
class InstrumentFlowPromoter(
    private val store: InstrumentFlowStore,
) {
    fun promote(now: Instant = Instant.now()): PromotionReport {
        val instruments = store.instrumentIds()
        val quarantined = mutableListOf<StagedTransfer>()
        val deferred = mutableListOf<StagedTransfer>()
        var promoted = 0
        for (row in store.unpromotedTransfers()) {
            val instrumentId = instruments[InstrumentKey(row.chain, row.mintAddress)]
            if (instrumentId == null) {
                quarantined += row
                continue
            }
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
        return PromotionReport(promoted, quarantined, deferred)
    }
}
