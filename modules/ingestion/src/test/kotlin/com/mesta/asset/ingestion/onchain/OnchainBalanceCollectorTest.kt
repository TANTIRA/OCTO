package com.mesta.asset.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.helius.HeliusException
import com.mesta.asset.ingestion.onchain.helius.HeliusRpcApi
import com.mesta.asset.ingestion.onchain.helius.HeliusWalletApi
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
private const val WALLET2 = "9gWfZ5xJ9kN8vB6tY3rQ1mP4sD7fH2jL8uK5iE3wA6bN"
private val AS_OF = Instant.parse("2025-06-01T00:00:00Z")

private class FakeSnapshotStore(
    vararg wallets: String,
) : OnchainStagingStore {
    val watched = wallets.map { WatchSource(CHAIN_SOLANA, it, null, null) }
    val snapshots = mutableListOf<OnchainBalance>()

    override fun activeWatchedAddresses(chain: String) = watched

    override fun newestSignature(
        chain: String,
        wallet: String,
    ): String? = null

    override fun insertTransfers(
        transfers: List<OnchainTransfer>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ) = 0

    override fun insertSnapshots(
        balances: List<OnchainBalance>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        snapshots += balances
        return balances.size
    }

    override fun latestSnapshots(
        chain: String,
        wallet: String,
    ) = snapshots.filter { it.chain == chain && it.wallet == wallet }
}

private class StubWalletApi(
    private val pages: Map<Pair<String, Int>, JsonNode>,
    private val failures: Set<String> = emptySet(),
) : HeliusWalletApi {
    val calls = mutableListOf<Pair<String, Int>>()

    override fun balances(
        address: String,
        page: Int,
    ): JsonNode {
        calls += address to page
        if (address in failures) throw HeliusException("boom", 500)
        return pages[address to page] ?: ObjectMapper().readTree("""{"balances":[],"pagination":{"hasMore":false}}""")
    }

    override fun transfers(
        address: String,
        limit: Int,
        before: String?,
    ): JsonNode = throw UnsupportedOperationException()

    override fun history(
        address: String,
        limit: Int,
        before: String?,
    ): JsonNode = throw UnsupportedOperationException()
}

private class StubRpc(
    private val lamports: Long = 0,
    private val accounts: JsonNode = ObjectMapper().readTree("""{"value":[]}"""),
    private val fail: Boolean = false,
) : HeliusRpcApi {
    override fun signaturesForAddress(
        address: String,
        limit: Int,
        before: String?,
        until: String?,
    ): JsonNode = throw UnsupportedOperationException()

    override fun transaction(signature: String): JsonNode? = null

    override fun balance(address: String): Long {
        if (fail) throw HeliusException("rpc boom", 500)
        return lamports
    }

    override fun tokenAccountsByOwner(address: String): JsonNode {
        if (fail) throw HeliusException("rpc boom", 500)
        return accounts
    }
}

private fun balancesPage(
    hasMore: Boolean,
    vararg mints: Pair<String, String>,
): JsonNode =
    ObjectMapper().readTree(
        """{"balances":[${mints.joinToString(
            ",",
        ) { (m, b) -> """{"mint":"$m","balance":$b,"decimals":6,"tokenProgram":"spl-token"}""" }}],"pagination":{"hasMore":$hasMore}}""",
    )

class OnchainBalanceCollectorTest {
    @Test
    fun `every watched wallet is collected and pages until hasMore is false`() {
        val walletApi =
            StubWalletApi(
                mapOf(
                    (WALLET to 1) to balancesPage(true, "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to "10"),
                    (WALLET to 2) to balancesPage(false, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "5"),
                ),
            )
        val store = FakeSnapshotStore(WALLET, WALLET2)
        val report = OnchainBalanceCollector(store, walletApi, null).collect(asOf = AS_OF)

        assertEquals(2, report.walletsSeen)
        assertEquals(2, report.snapshotsInserted)
        assertEquals(listOf(WALLET to 1, WALLET to 2), walletApi.calls.filter { it.first == WALLET })
        assertEquals(BigInteger("10000000"), store.snapshots[0].amountRaw)
    }

    @Test
    fun `a wallet-api failure falls back to rpc when wired`() {
        val accounts =
            ObjectMapper().readTree(
                """{"value":[{"account":{"data":{"parsed":{"info":{"mint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v","tokenAmount":{"amount":"700","decimals":6}}}}}}]}""",
            )
        val walletApi = StubWalletApi(emptyMap(), failures = setOf(WALLET))
        val store = FakeSnapshotStore(WALLET)
        val report = OnchainBalanceCollector(store, walletApi, StubRpc(2_000_000_000, accounts)).collect(asOf = AS_OF)

        assertEquals(listOf(WALLET), report.rpcFallbacks)
        assertEquals(2, report.snapshotsInserted)
        assertTrue(store.snapshots.all { it.source == BalanceSource.RPC })
    }

    @Test
    fun `a wallet failing both paths lands in failedWallets and the run continues`() {
        val walletApi =
            StubWalletApi(
                mapOf(WALLET2 to 1 to balancesPage(false, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "3")),
                failures = setOf(WALLET),
            )
        val store = FakeSnapshotStore(WALLET, WALLET2)
        val report = OnchainBalanceCollector(store, walletApi, StubRpc(fail = true)).collect(asOf = AS_OF)

        assertEquals(listOf(WALLET), report.failedWallets)
        assertEquals(1, report.snapshotsInserted)
        assertEquals(WALLET2, store.snapshots.single().wallet)
    }
}
