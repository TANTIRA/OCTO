package com.mesta.asset.ingestion.onchain

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val WH_WALLET = "7VVA" + "B".repeat(39)
private val WH_OTHER = "8WWB" + "C".repeat(39)
private val WH_ATA = "ATAx" + "E".repeat(39)
private val WH_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
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

/** Only the wallet's ATA is in accountKeys; the token balance names the wallet as owner. */
private fun ataDelivery(
    ata: String,
    owner: String,
    pre: Long,
    post: Long,
): String =
    """
    [{
      "slot": 250000000,
      "blockTime": 1726000000,
      "transaction": {
        "signatures": ["$WH_SIG"],
        "message": {"recentBlockhash": "bh123", "accountKeys": [{"pubkey":"$ata"},{"pubkey":"$WH_OTHER"}]}
      },
      "meta": {
        "err": null,
        "preBalances": [0,0],
        "postBalances": [0,0],
        "preTokenBalances": [{"accountIndex":0,"mint":"$WH_MINT","owner":"$owner","uiTokenAmount":{"amount":"$pre","decimals":6}}],
        "postTokenBalances": [{"accountIndex":0,"mint":"$WH_MINT","owner":"$owner","uiTokenAmount":{"amount":"$post","decimals":6}}]
      }
    }]
    """.trimIndent()

private class FakeWebhookStore : OnchainStagingStore {
    var watched: List<WatchSource> = emptyList()
    var tokenOwners: Map<String, String> = emptyMap()
    val inserted = mutableListOf<OnchainTransfer>()
    var actorSeen: String? = null

    override fun activeWatchedAddresses(chain: String) = watched

    override fun newestSlot(
        chain: String,
        wallet: String,
    ): Long? = null

    override fun watchedTokenAccounts(chain: String): Map<String, String> = tokenOwners

    override fun newestStagedSlot(chain: String): Long? = null

    override fun tokenContracts(chain: String): List<TokenContract> = emptyList()

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

    override fun insertEvidence(
        evidence: List<OnchainEvidence>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int = evidence.size
}

class OnchainWebhookServiceTest {
    private val mapper = ObjectMapper()
    private val store = FakeWebhookStore()
    private var finalized: Set<String>? = null
    private val service = OnchainWebhookService(store, FinalityProbe { finalized ?: it.toSet() })

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
    fun `a delivery touching only a watched wallet ATA resolves to the owner`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        store.tokenOwners = mapOf(WH_ATA to WH_WALLET)
        val json = ataDelivery(WH_ATA, WH_WALLET, pre = 1_000, post = 5_000)

        assertEquals(1, ingest(json))
        assertEquals(WH_WALLET, store.inserted.single().wallet)
        assertEquals("solana:$WH_SIG:$WH_ATA:tok:0", store.inserted.single().externalId)
    }

    @Test
    fun `an ATA whose owner is not watched stages nothing`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_OTHER, tenantId = null, label = null))
        store.tokenOwners = mapOf(WH_ATA to WH_WALLET) // WH_WALLET is unwatched
        val json = ataDelivery(WH_ATA, WH_WALLET, pre = 1_000, post = 5_000)

        assertEquals(0, ingest(json))
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun `non-array payloads ingest nothing`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        assertEquals(0, ingest("""{"unexpected": true}"""))
    }

    @Test
    fun `a delivery that is not yet finalized stages nothing`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        finalized = emptySet()
        val json = delivery(listOf(WH_WALLET), pre = listOf(1_000), post = listOf(2_000))

        assertEquals(0, ingest(json))
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun `a transaction without a signature cannot be verified and is dropped`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        val unsigned =
            delivery(listOf(WH_WALLET), pre = listOf(1_000), post = listOf(2_000))
                .replace("\"signatures\": [\"$WH_SIG\"]", "\"signatures\": []")

        assertEquals(0, ingest(unsigned))
        assertTrue(store.inserted.isEmpty())
    }
}
