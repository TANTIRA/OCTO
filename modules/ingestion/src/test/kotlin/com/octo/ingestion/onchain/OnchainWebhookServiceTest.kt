package com.octo.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
    val inserted = mutableListOf<OnchainTransfer>()
    var actorSeen: String? = null

    override fun activeWatchedAddresses(chain: String) = watched

    override fun newestSlot(
        chain: String,
        wallet: String,
    ): Long? = null

    override fun newestStagedSlot(chain: String): Long? = null

    override fun scannedThrough(chain: String): Long? = null

    override fun recordScannedThrough(
        chain: String,
        block: Long,
    ) = Unit

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

/** A clock tests can move, so deferred-entry expiry is asserted rather than slept through. */
private class MutableClock(
    private var now: Instant,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

class OnchainWebhookServiceTest {
    private val mapper = ObjectMapper()
    private val store = FakeWebhookStore()
    private var finalized: Set<String>? = null
    private val clock = MutableClock(Instant.parse("2026-10-02T00:00:00Z"))

    /** Signature -> the chain's "own copy". Auto-filled from each delivery unless a test overrides it first. */
    private val canonical = mutableMapOf<String, JsonNode>()
    private var service =
        OnchainWebhookService(
            store,
            FinalityProbe { finalized ?: it.toSet() },
            TransactionFetcher { canonical[it] },
            clock = clock,
        )

    private fun ingest(json: String): Int {
        val tree = mapper.readTree(json)
        if (tree.isArray) {
            tree.forEach { tx ->
                tx
                    .path("transaction")
                    .path("signatures")
                    .path(0)
                    .asText(null)
                    ?.let { sig -> canonical.putIfAbsent(sig, tx) }
            }
        }
        return service.ingest(tree, UUID.randomUUID(), UUID.randomUUID()).staged
    }

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
        // No token-owner snapshot map exists — the wallet is matched as the token
        // balance's owner straight from the transaction's own meta (#484).
        val json = ataDelivery(WH_ATA, WH_WALLET, pre = 1_000, post = 5_000)

        assertEquals(1, ingest(json))
        assertEquals(WH_WALLET, store.inserted.single().wallet)
        assertEquals("solana:$WH_SIG:$WH_ATA:tok:0", store.inserted.single().externalId)
    }

    @Test
    fun `an ATA whose owner is not watched stages nothing`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_OTHER, tenantId = null, label = null))
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
    fun `a delivery that is not yet finalized is held and stages on re-check`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        finalized = emptySet()
        val json = delivery(listOf(WH_WALLET), pre = listOf(1_000), post = listOf(2_000))

        val result = service.ingest(mapper.readTree(json), UUID.randomUUID(), UUID.randomUUID())
        assertEquals(0, result.staged)
        assertEquals(1, result.deferred)
        assertTrue(store.inserted.isEmpty())

        // The chain finalizes between deliveries; the re-check pass stages it.
        finalized = setOf(WH_SIG)
        canonical[WH_SIG] = mapper.readTree(json)[0]
        val recheck = service.recheckDeferred()

        assertEquals(1, recheck.staged)
        assertEquals(0, recheck.pending)
        assertEquals(BigInteger.valueOf(1_000), store.inserted.single().amountRaw)
    }

    @Test
    fun `a deferred signature that never finalizes expires instead of retrying forever`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        finalized = emptySet()
        val json = delivery(listOf(WH_WALLET), pre = listOf(1_000), post = listOf(2_000))

        val result = service.ingest(mapper.readTree(json), UUID.randomUUID(), UUID.randomUUID())
        assertEquals(1, result.deferred)

        clock.advance(Duration.ofMinutes(6))
        val recheck = service.recheckDeferred()

        assertEquals(1, recheck.expired)
        assertEquals(0, recheck.pending)
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun `a full re-check set rejects further signatures so the caller can ask for a retry`() {
        service =
            OnchainWebhookService(
                store,
                FinalityProbe { finalized ?: it.toSet() },
                TransactionFetcher { canonical[it] },
                clock = clock,
                maxDeferred = 1,
            )
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        finalized = emptySet()
        val tx2 = mapper.readTree(delivery(listOf(WH_WALLET), pre = listOf(1_000), post = listOf(2_000)))[0]
        (tx2.path("transaction") as com.fasterxml.jackson.databind.node.ObjectNode)
            .putArray("signatures")
            .add("5wHuPkQ" + "x".repeat(80))
        val twoPending =
            mapper
                .createArrayNode()
                .add(mapper.readTree(delivery(listOf(WH_WALLET), pre = listOf(1_000), post = listOf(2_000)))[0])
                .add(tx2)

        val result = service.ingest(twoPending, UUID.randomUUID(), UUID.randomUUID())

        assertEquals(1, result.deferred)
        assertEquals(1, result.rejected)
        assertEquals(1, service.deferredSize())
    }

    @Test
    fun `a finalized signature that cannot be fetched is held and stages once fetchable`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        val json = delivery(listOf(WH_WALLET), pre = listOf(1_000), post = listOf(2_000))
        // Call the service directly, bypassing ingest()'s auto-fill, so the fetcher returns null for WH_SIG.
        val result = service.ingest(mapper.readTree(json), UUID.randomUUID(), UUID.randomUUID())

        assertEquals(0, result.staged)
        assertEquals(1, result.deferred)
        assertTrue(store.inserted.isEmpty())

        canonical[WH_SIG] = mapper.readTree(json)[0]
        val recheck = service.recheckDeferred()

        assertEquals(1, recheck.staged)
        assertEquals(0, recheck.pending)
        assertEquals(1, store.inserted.size)
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

    @Test
    fun `a forged delivery payload is ignored in favor of the chain's own copy of the signature`() {
        store.watched = listOf(WatchSource(chain = CHAIN_SOLANA, address = WH_WALLET, tenantId = null, label = null))
        // The chain's own copy of WH_SIG shows no transfer to the watched wallet at all.
        canonical[WH_SIG] = mapper.readTree(delivery(listOf(WH_OTHER), pre = listOf(5_000), post = listOf(4_000)))[0]
        // A malicious/compromised sender pairs that real, finalized signature with a fabricated
        // payload claiming a large transfer into the watched wallet.
        val forged = delivery(listOf(WH_WALLET, WH_OTHER), pre = listOf(1_000, 5_000), post = listOf(2_000_000_000, 4_000))

        assertEquals(0, ingest(forged))
        assertTrue(store.inserted.isEmpty())
    }
}
