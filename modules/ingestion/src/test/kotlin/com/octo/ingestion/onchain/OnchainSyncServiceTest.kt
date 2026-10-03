package com.octo.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.ingestion.onchain.helius.HeliusRpcApi
import com.octo.ingestion.onchain.helius.HeliusTransferNormalizer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val WALLET = "7VVA" + "A".repeat(39)
private val ATA = "ATAx" + "D".repeat(39)
private val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

private class FakeRpc(
    private val pages: List<Pair<List<JsonNode>, String?>>,
) : HeliusRpcApi {
    data class PageCall(
        val address: String,
        val token: String?,
        val slotGt: Long?,
        val slotLte: Long?,
    )

    val txCalls = mutableListOf<PageCall>()

    override fun signaturesForAddress(
        address: String,
        limit: Int,
        before: String?,
        until: String?,
    ): JsonNode = ObjectMapper().createArrayNode()

    override fun transaction(signature: String): JsonNode? = null

    override fun transactionsForAddress(
        address: String,
        limit: Int,
        paginationToken: String?,
        slotGt: Long?,
        slotLte: Long?,
    ): JsonNode {
        txCalls += PageCall(address, paginationToken, slotGt, slotLte)
        val (txs, next) = pages.getOrNull(txCalls.size - 1) ?: (emptyList<JsonNode>() to null)
        return ObjectMapper().createObjectNode().apply {
            set<JsonNode>("data", ObjectMapper().createArrayNode().apply { txs.forEach { add(it) } })
            if (next != null) put("paginationToken", next)
        }
    }

    override fun balance(address: String): Long = 0

    override fun tokenAccountsByOwner(address: String): JsonNode = ObjectMapper().createArrayNode()

    override fun stakeAccounts(address: String) =
        com.fasterxml.jackson.databind
            .ObjectMapper()
            .createArrayNode()

    override fun inflationReward(
        addresses: List<String>,
        epoch: Long?,
    ) = com.fasterxml.jackson.databind
        .ObjectMapper()
        .createArrayNode()

    override fun blockTime(slot: Long): Long? = null

    override fun tokenSupply(mint: String) = ObjectMapper().createObjectNode()

    override fun tokenLargestAccounts(mint: String) = ObjectMapper().createArrayNode()

    override fun signatureStatuses(signatures: List<String>) = ObjectMapper().createObjectNode()
}

private open class FakeStore : OnchainStagingStore {
    var watched = listOf(WatchSource(CHAIN_SOLANA, WALLET, null, null))
    var cursorSlot: Long? = null
    var frontier: SyncFrontier? = null
    val newestSlotActors = mutableListOf<String>()
    val batches = mutableListOf<List<String>>()

    override fun activeWatchedAddresses(chain: String): List<WatchSource> = watched

    override fun newestSlot(
        chain: String,
        wallet: String,
        actor: String,
    ): Long? {
        newestSlotActors += actor
        return cursorSlot
    }

    override fun syncFrontier(
        chain: String,
        wallet: String,
    ): SyncFrontier? = frontier

    override fun saveSyncFrontier(frontier: SyncFrontier) {
        this.frontier = frontier
    }

    override fun clearSyncFrontier(
        chain: String,
        wallet: String,
    ) {
        frontier = null
    }

    override fun newestStagedSlot(chain: String): Long? = null

    override fun scanCheckpoint(chain: String): Long? = null

    override fun saveScanCheckpoint(
        chain: String,
        block: Long,
    ) {
    }

    override fun tokenContracts(chain: String): List<TokenContract> = emptyList()

    override fun insertTransfers(
        transfers: List<OnchainTransfer>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        batches += transfers.map { it.externalId }
        return transfers.size
    }

    override fun insertSnapshots(
        balances: List<OnchainBalance>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int = balances.size

    override fun latestSnapshots(
        chain: String,
        wallet: String,
    ): List<OnchainBalance> = emptyList()

    override fun insertEvidence(
        evidence: List<OnchainEvidence>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int = evidence.size
}

private fun solTx(
    sig: String,
    wallet: String,
    pre: Long,
    post: Long,
    slot: Long = 250000001,
): JsonNode =
    ObjectMapper().readTree(
        """
        {"slot":$slot,"blockTime":1726000000,
         "transaction":{"signatures":["$sig"],"message":{"accountKeys":[{"pubkey":"$wallet"}]}},
         "meta":{"err":null,"preBalances":[$pre],"postBalances":[$post],"preTokenBalances":[],"postTokenBalances":[]}}
        """.trimIndent(),
    )

/** The wallet never appears in accountKeys — only its ATA does, as the token balance's owner. */
private fun ataTx(
    sig: String,
    ata: String,
    owner: String,
    pre: Long,
    post: Long,
): JsonNode =
    ObjectMapper().readTree(
        """
        {"slot":250000002,"blockTime":1726000100,
         "transaction":{"signatures":["$sig"],"message":{"accountKeys":[{"pubkey":"$ata"},{"pubkey":"counterparty"}]}},
         "meta":{"err":null,"preBalances":[0,0],"postBalances":[0,0],
          "preTokenBalances":[{"accountIndex":0,"mint":"$MINT","owner":"$owner","uiTokenAmount":{"amount":"$pre","decimals":6}}],
          "postTokenBalances":[{"accountIndex":0,"mint":"$MINT","owner":"$owner","uiTokenAmount":{"amount":"$post","decimals":6}}]}}
        """.trimIndent(),
    )

class OnchainSyncServiceTest {
    private val normalizer = HeliusTransferNormalizer()

    @Test
    fun `first sync backfills with no cursor`() {
        val rpc =
            FakeRpc(
                listOf(
                    listOf(solTx("sig1", WALLET, 0, 100), solTx("sig2", WALLET, 100, 300)) to null,
                ),
            )
        val store = FakeStore()
        val service = OnchainSyncService(rpc, normalizer, store, pageLimit = 100)

        val results = service.syncAll()
        assertEquals(1, results.size)
        assertEquals(2, results[0].signaturesSeen)
        assertEquals(2, results[0].transfersStaged)
        assertEquals(null, rpc.txCalls[0].slotGt) // no slot filter on a cold wallet
        assertEquals(2, store.batches.single().size)
    }

    @Test
    fun `incremental sync passes the newest staged slot as the slot filter`() {
        val rpc = FakeRpc(listOf(listOf(solTx("sigNew", WALLET, 0, 50)) to null))
        val store = FakeStore().apply { cursorSlot = 250_000_010L }
        val service = OnchainSyncService(rpc, normalizer, store, pageLimit = 100)

        service.syncAll()
        assertEquals(250_000_010L, rpc.txCalls[0].slotGt)
    }

    @Test
    fun `a second page is fetched with the pagination token`() {
        val rpc =
            FakeRpc(
                listOf(
                    listOf(solTx("sigA", WALLET, 0, 1), solTx("sigB", WALLET, 1, 2)) to "100:2",
                    listOf(solTx("sigC", WALLET, 2, 3)) to null,
                ),
            )
        val store = FakeStore()
        val service = OnchainSyncService(rpc, normalizer, store, pageLimit = 2)

        val result = service.syncAll().single()
        assertEquals(3, result.signaturesSeen)
        assertEquals("100:2", rpc.txCalls[1].token)
    }

    @Test
    fun `a transaction with no legs for the wallet is seen but stages nothing`() {
        val failed =
            ObjectMapper().readTree(
                """
                {"slot":250000003,"blockTime":1726000200,
                 "transaction":{"signatures":["sigFail"],"message":{"accountKeys":[{"pubkey":"$WALLET"}]}},
                 "meta":{"err":{"InstructionError":[0,"Custom"]},"preBalances":[9],"postBalances":[4],
                  "preTokenBalances":[],"postTokenBalances":[]}}
                """.trimIndent(),
            )
        val rpc = FakeRpc(listOf(listOf(failed, solTx("sigOk", WALLET, 0, 7)) to null))
        val store = FakeStore()
        val result = OnchainSyncService(rpc, normalizer, store).syncAll().single()
        assertEquals(2, result.signaturesSeen)
        assertEquals(1, result.transfersStaged)
    }

    @Test
    fun `a transfer touching only the wallet ATA is ingested for the owner`() {
        // Regression: getSignaturesForAddress could never return this transaction — the wallet
        // is absent from accountKeys. The balanceChanged filter returns it; the normalizer
        // attributes the leg to the owner via meta's owner field.
        val rpc = FakeRpc(listOf(listOf(ataTx("sigAta", ATA, WALLET, 1_000, 5_000)) to null))
        val store = FakeStore()
        val result = OnchainSyncService(rpc, normalizer, store).syncAll().single()
        assertEquals(1, result.transfersStaged)
        assertEquals("solana:sigAta:$ATA:tok:0", store.batches.single().single())
    }

    @Test
    fun `duplicate legs stage zero on replay`() {
        val tx = solTx("sigDup", WALLET, 0, 100)
        val rpc = FakeRpc(listOf(listOf(tx) to null))
        val store =
            object : FakeStore() {
                override fun insertTransfers(
                    transfers: List<OnchainTransfer>,
                    ingestionRunId: UUID,
                    correlationId: UUID,
                    actor: String,
                ): Int {
                    batches += transfers.map { it.externalId }
                    return 0 // unique key refused every row
                }
            }
        val result = OnchainSyncService(rpc, normalizer, store).syncAll().single()
        assertEquals(0, result.transfersStaged)
        assertTrue(store.batches.single().isNotEmpty())
    }

    @Test
    fun `legs carry the normalized transfer shape`() {
        val rpc = FakeRpc(listOf(listOf(solTx("sigX", WALLET, 10, 110)) to null))
        val store = FakeStore()
        OnchainSyncService(rpc, normalizer, store).syncAll()
        val id = store.batches.single().single()
        assertEquals("solana:sigX:$WALLET:bal:0", id)
    }

    @Test
    fun `a truncated descent stores a frontier and the next pass resumes below its ceiling`() {
        // #509: more new transactions than the page budget used to strand the gap's low end
        // forever — the pass resumes where it stopped instead of restarting at the top.
        val rpc =
            FakeRpc(
                listOf(
                    listOf(solTx("sigNew", WALLET, 0, 5, slot = 250000090)) to "tok1",
                    listOf(solTx("sigMid", WALLET, 5, 9, slot = 250000060)) to null,
                ),
            )
        val store = FakeStore().apply { cursorSlot = 250_000_010L }
        val service = OnchainSyncService(rpc, normalizer, store, pageLimit = 100, maxPages = 1)

        service.syncAll()

        val frontier = store.frontier
        assertEquals(SyncFrontier(CHAIN_SOLANA, WALLET, floorSlot = 250_000_010L, ceilingSlot = 250_000_090L), frontier)

        service.syncAll()

        // The resumed pass descends inside the recorded bounds — it does not re-ask for the top.
        assertEquals(250_000_010L, rpc.txCalls[1].slotGt)
        assertEquals(250_000_090L, rpc.txCalls[1].slotLte)
        assertEquals(null, rpc.txCalls[1].token)
        assertEquals(null, store.frontier)
    }

    @Test
    fun `a cold wallet that keeps paging descends to genesis across passes`() {
        val rpc =
            FakeRpc(
                listOf(
                    listOf(solTx("sigHi", WALLET, 0, 5, slot = 250000090)) to "tok1",
                    listOf(solTx("sigLo", WALLET, 5, 9, slot = 250000001)) to null,
                ),
            )
        val store = FakeStore()
        val service = OnchainSyncService(rpc, normalizer, store, pageLimit = 100, maxPages = 1)

        service.syncAll()
        service.syncAll()

        // floorSlot stays null: the descent of a wallet the poller never synced is unbounded below.
        assertEquals(null, rpc.txCalls[1].slotGt)
        assertEquals(250_000_090L, rpc.txCalls[1].slotLte)
        assertEquals(null, store.frontier)
    }

    @Test
    fun `the incremental cursor is scoped to the poller's own actor`() {
        // #509: webhook-staged rows must not move the poller's cursor — newestSlot answers
        // for the actor the pass writes with, not the newest staged slot overall.
        val rpc = FakeRpc(listOf(listOf(solTx("sigNew", WALLET, 0, 50)) to null))
        val store = FakeStore().apply { cursorSlot = 250_000_010L }
        OnchainSyncService(rpc, normalizer, store).syncAddress(store.watched.single(), actor = "helius-poller")

        assertEquals(listOf("helius-poller"), store.newestSlotActors)
    }
}
