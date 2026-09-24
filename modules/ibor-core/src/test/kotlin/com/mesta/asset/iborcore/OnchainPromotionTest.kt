package com.mesta.asset.iborcore

import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SOL = UUID.randomUUID()
private val BONK = UUID.randomUUID()
private const val BONK_MINT = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"
private const val WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"

private class FakeStore : InstrumentFlowStore {
    val staged = mutableListOf<StagedTransfer>()
    val flows = mutableListOf<InstrumentFlow>()
    val instruments = mutableMapOf(InstrumentKey("solana", null) to SOL, InstrumentKey("solana", BONK_MINT) to BONK)

    fun stage(rows: List<StagedTransfer>) = staged.addAll(rows)

    override fun unpromotedTransfers() =
        staged
            .filter { s -> flows.none { it.externalId == s.externalId } }
            .sortedWith(compareBy({ it.recordedAt }, { it.id }))

    override fun instrumentIds() = instruments

    override fun flowIdForStaging(stagingRowId: UUID): UUID? {
        val stagedRow = staged.firstOrNull { it.id == stagingRowId } ?: return null
        return flows.firstOrNull { it.externalId == stagedRow.externalId }?.id
    }

    override fun insertFlow(
        flow: InstrumentFlow,
        sourceSystem: String,
        ingestionRunId: UUID,
        correlationId: UUID,
    ): Boolean {
        if (flows.any { it.externalId == flow.externalId }) return false
        flows += flow
        return true
    }

    override fun flowsFor(
        chain: String,
        wallet: String,
    ) = flows.filter { it.chain == chain && it.wallet == wallet }
}

private fun staged(
    externalId: String,
    kind: String,
    amount: String = "100",
    mint: String? = null,
    supersedes: UUID? = null,
    rationale: String? = null,
    recorded: String = "2025-01-10T00:00:10Z",
) = StagedTransfer(
    id = UUID.randomUUID(),
    externalId = externalId,
    chain = "solana",
    signature = "sig-$externalId",
    slot = 100,
    blockTime = Instant.parse("2025-01-10T00:00:00Z"),
    wallet = WALLET,
    tokenAccount = null,
    mintAddress = mint,
    amountRaw = BigInteger(amount),
    decimals = 9,
    transferKind = kind,
    supersedesId = supersedes,
    rationale = rationale,
    recordedAt = Instant.parse(recorded),
    sourceSystem = "helius-solana",
    ingestionRunId = UUID.randomUUID(),
    correlationId = UUID.randomUUID(),
)

class OnchainPromotionTest {
    private val now = Instant.parse("2025-06-01T00:00:00Z")

    @Test
    fun `finalized staging rows promote to instrument flows`() {
        val store = FakeStore()
        store.stage(listOf(staged("solana:s1:w:bal:0", "transfer-in"), staged("solana:s2:w:bal:0", "transfer-out", "30")))

        val report = InstrumentFlowPromoter(store).promote(now)

        assertEquals(2, report.promoted)
        val inFlow = store.flows.first { it.externalId == "solana:s1:w:bal:0" }
        assertEquals(InstrumentFlowType.TRANSFER_IN, inFlow.flowType)
        assertEquals(Instant.parse("2025-01-10T00:00:00Z"), inFlow.occurredAt)
        assertEquals(now, inFlow.recordedAt)
        assertEquals(BigInteger("70"), tokenPositions(store.flowsFor("solana", WALLET), now).single().netRaw)
    }

    @Test
    fun `unknown mints quarantine instead of becoming instruments`() {
        val unknownMint = "So11111111111111111111111111111111111111199"
        val store = FakeStore()
        store.stage(listOf(staged("solana:s1:w:tok:1", "transfer-in", mint = unknownMint), staged("solana:s2:w:bal:0", "transfer-in")))

        val report = InstrumentFlowPromoter(store).promote(now)

        assertEquals(1, report.promoted)
        assertEquals(listOf(unknownMint), report.quarantined.map { it.mintAddress })
        assertTrue(store.flows.none { it.externalId == "solana:s1:w:tok:1" })
    }

    @Test
    fun `a correction defers until its original is promoted then supersedes it`() {
        val original = staged("solana:s1:w:bal:0", "transfer-in")
        val correction =
            staged(
                "solana:s1:w:bal:0:fix",
                "transfer-in",
                "150",
                supersedes = original.id,
                rationale = "restated amount",
                recorded = "2025-02-01T00:00:10Z",
            )
        val store = FakeStore()
        store.stage(listOf(correction))
        val promoter = InstrumentFlowPromoter(store)

        val first = promoter.promote(now)

        assertEquals(0, first.promoted)
        assertEquals(listOf(correction), first.deferred)

        store.stage(listOf(original))
        val second = promoter.promote(now)

        assertEquals(2, second.promoted)
        val originalFlow = store.flows.first { it.externalId == original.externalId }
        val correctionFlow = store.flows.first { it.externalId == correction.externalId }
        assertEquals(originalFlow.id, correctionFlow.supersedesId)
        assertEquals("restated amount", correctionFlow.rationale)
        assertNull(originalFlow.rationale)
        assertEquals(BigInteger("150"), tokenPositions(store.flows, now).single().netRaw)
    }

    @Test
    fun `replayed staging rows are not promoted twice`() {
        val store = FakeStore()
        store.stage(listOf(staged("solana:s1:w:bal:0", "transfer-in")))
        val promoter = InstrumentFlowPromoter(store)

        promoter.promote(now)
        val second = promoter.promote(now)

        assertEquals(0, second.promoted)
        assertEquals(1, store.flows.size)
    }
}
