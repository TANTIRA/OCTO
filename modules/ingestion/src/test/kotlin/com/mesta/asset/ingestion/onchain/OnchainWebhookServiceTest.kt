package com.mesta.asset.ingestion.onchain

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val WH_WALLET = "7VVA" + "B".repeat(39)
private val WH_OTHER = "8WWB" + "C".repeat(39)
private val WH_SIG = "5wHuPkQ" + "w".repeat(80)

private fun delivery(
    accounts: List<String>,
    pre: List<Long>,
    post: List<Long>,
    stringKeys: Boolean = false,
): String {
    val keys =
        if (stringKeys) {
            accounts.joinToString(",") { "\"$it\"" }
        } else {
            accounts.joinToString(",") { """{"pubkey":"$it","signer":false}""" }
        }
    return """
        [{
          "slot": 250000000,
          "blockTime": 1726000000,
          "transaction": {
            "signatures": ["$WH_SIG"],
            "message": {"recentBlockhash": "bh123", "accountKeys": [$keys]}
          },
          "meta": {
            "err": null,
            "preBalances": [${pre.joinToString(",")}],
            "postBalances": [${post.joinToString(",")}],
            "preTokenBalances": [],
            "postTokenBalances": []
          }
        }]
        """.trimIndent()
}

private class FakeWebhookStore : OnchainStagingStore {
    var watched: List<WatchSource> = emptyList()
    val inserted = mutableListOf<OnchainTransfer>()
    var actorSeen: String? = null

    override fun activeWatchedAddresses(chain: String) = watched

    override fun newestSignature(
        chain: String,
        wallet: String,
    ) = null

    override fun insertTransfers(
        transfers: List<OnchainTransfer>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        inserted += transfers
        actorSeen = actor
        return transfers.size
    }

    override fun insertSnapshots(
        balances: List<OnchainBalance>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ) = balances.size

    override fun latestSnapshots(
        chain: String,
        wallet: String,
    ): List<OnchainBalance> = emptyList()
}

class OnchainWebhookServiceTest {
    private val mapper = ObjectMapper()
    private val store = FakeWebhookStore()
    private val service = OnchainWebhookService(store)

    private fun ingest(json: String) = service.ingest(mapper.readTree(json), UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `a delivery for a watched wallet stages its normalized legs`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        val json = delivery(listOf(WH_WALLET, WH_OTHER), pre = listOf(1_000, 5_000), post = listOf(2_000_000_000, 4_000))

        val written = ingest(json)

        assertEquals(1, written)
        assertEquals(BigInteger.valueOf(1_999_999_000), store.inserted.single().amountRaw)
        assertEquals("helius-webhook", store.actorSeen)
    }

    @Test
    fun `deliveries naming no watched address stage nothing`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        val json = delivery(listOf(WH_OTHER), pre = listOf(5_000), post = listOf(4_000))

        assertEquals(0, ingest(json))
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun `a delivery touching two watched wallets normalizes legs for each`() {
        store.watched =
            listOf(
                WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null),
                WatchSource(chain = CHAIN_SOLANA, address = WH_OTHER, tenantId = null, label = null),
            )
        val json = delivery(listOf(WH_WALLET, WH_OTHER), pre = listOf(1_000, 5_000), post = listOf(2_000_000_000, 4_000))

        assertEquals(2, ingest(json))
        assertEquals(setOf(WH_WALLET, WH_OTHER), store.inserted.map { it.wallet }.toSet())
    }

    @Test
    fun `plain-string accountKeys are matched too`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        val json = delivery(listOf(WH_WALLET), pre = listOf(1_000), post = listOf(2_000), stringKeys = true)

        assertEquals(1, ingest(json))
    }

    @Test
    fun `non-array payloads ingest nothing`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        assertEquals(0, ingest("""{"unexpected": true}"""))
    }
}
