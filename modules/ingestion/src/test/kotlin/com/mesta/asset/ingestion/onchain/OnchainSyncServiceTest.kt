package com.mesta.asset.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.helius.HeliusRpcApi
import com.mesta.asset.ingestion.onchain.helius.HeliusTransferNormalizer
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
    val txCalls = mutableListOf<Triple<String, String?, Long?>>()

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
    ): JsonNode {
        txCalls += Triple(address, paginationToken, slotGt)
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
}

private open class FakeStore : OnchainStagingStore {
    var watched = listOf(WatchSource(CHAIN_SOLANA, WALLET, null, null))
    var cursorSlot: Long? = null
    val batches = mutableListOf<List<String>>()

    override fun activeWatchedAddresses(chain: String): List<WatchSource> = watched

    override fun newestSlot(
        chain: String,
        wallet: String,
    ): Long? = cursorSlot

    override fun watchedTokenAccounts(chain: String): Map<String, String> = emptyMap()

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
): JsonNode =
    ObjectMapper().readTree(
        """
        {"slot":250000001,"blockTime":1726000000,
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
    fun `first sync backfills with no until cursor`() {
        val rpc =
            FakeRpc(
                listOf(listOf("sig1", "sig2")),
                mapOf(
                    "sig1" to solTx("sig1", WALLET, 0, 100),
                    "sig2" to solTx("sig2", WALLET, 100, 300),
                ),
            )
        val store = FakeStore()
        val service = OnchainSyncService(rpc, normalizer, store, pageLimit = 100)

        val results = service.syncAll()
        assertEquals(1, results.size)
        assertEquals(2, results[0].signaturesSeen)
        assertEquals(2, results[0].transfersStaged)
        assertEquals(null, rpc.signatureCalls[0].third) // no cursor on a cold wallet
        assertEquals(2, store.batches.single().size)
    }

    @Test
    fun `incremental sync passes the newest staged signature as until`() {
        val rpc = FakeRpc(listOf(listOf("sigNew")), mapOf("sigNew" to solTx("sigNew", WALLET, 0, 50)))
        val store = FakeStore().apply { cursor = "sigBoundary" }
        val service = OnchainSyncService(rpc, normalizer, store, pageLimit = 100)

        service.syncAll()
        assertEquals("sigBoundary", rpc.signatureCalls[0].third)
    }

    @Test
    fun `a second page is fetched with before set to the last signature`() {
        val rpc =
            FakeRpc(
                listOf(listOf("sigA", "sigB"), listOf("sigC")),
                mapOf("sigA" to solTx("sigA", WALLET, 0, 1), "sigB" to solTx("sigB", WALLET, 1, 2), "sigC" to solTx("sigC", WALLET, 2, 3)),
            )
        val store = FakeStore()
        val service = OnchainSyncService(rpc, normalizer, store, pageLimit = 2)

        val result = service.syncAll().single()
        assertEquals(3, result.signaturesSeen)
        assertEquals("sigB", rpc.signatureCalls[1].second)
    }

    @Test
    fun `a null transaction is skipped but the page continues`() {
        val rpc = FakeRpc(listOf(listOf("gone", "sigOk")), mapOf("sigOk" to solTx("sigOk", WALLET, 0, 7)))
        val store = FakeStore()
        val result = OnchainSyncService(rpc, normalizer, store).syncAll().single()
        assertEquals(2, result.signaturesSeen)
        assertEquals(1, result.transactionsFetched)
        assertEquals(1, result.transfersStaged)
    }

    @Test
    fun `duplicate legs stage zero on replay`() {
        val tx = solTx("sigDup", WALLET, 0, 100)
        val rpc = FakeRpc(listOf(listOf("sigDup")), mapOf("sigDup" to tx))
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

                override fun insertSnapshots(
                    balances: List<OnchainBalance>,
                    ingestionRunId: UUID,
                    correlationId: UUID,
                    actor: String,
                ): Int = 0

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
        val result = OnchainSyncService(rpc, normalizer, store).syncAll().single()
        assertEquals(0, result.transfersStaged)
        assertTrue(store.batches.single().isNotEmpty())
    }

    @Test
    fun `legs carry the normalized transfer shape`() {
        val rpc = FakeRpc(listOf(listOf("sigX")), mapOf("sigX" to solTx("sigX", WALLET, 10, 110)))
        val store = FakeStore()
        OnchainSyncService(rpc, normalizer, store).syncAll()
        val id = store.batches.single().single()
        assertEquals("solana:sigX:$WALLET:bal:0", id)
    }
}
