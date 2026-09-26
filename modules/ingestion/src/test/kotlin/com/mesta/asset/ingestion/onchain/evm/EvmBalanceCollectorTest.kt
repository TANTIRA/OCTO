package com.mesta.asset.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.BalanceSource
import com.mesta.asset.ingestion.onchain.CHAIN_ARBITRUM_ONE
import com.mesta.asset.ingestion.onchain.OnchainBalance
import com.mesta.asset.ingestion.onchain.OnchainEvidence
import com.mesta.asset.ingestion.onchain.OnchainStagingStore
import com.mesta.asset.ingestion.onchain.OnchainTransfer
import com.mesta.asset.ingestion.onchain.TokenContract
import com.mesta.asset.ingestion.onchain.WatchSource
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EB_WALLET = "0x1111111111111111111111111111111111111111"
private const val EB_WALLET2 = "0x2222222222222222222222222222222222222222"
private const val EB_USDC = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
private const val EB_USDCE = "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8"

private class BalanceFakeRpc : EvmRpcApi {
    var native = mutableMapOf<String, BigInteger>()
    var token = mutableMapOf<Pair<String, String>, BigInteger?>()
    var failing = mutableSetOf<String>()
    private val mapper = ObjectMapper()

    override fun chainId() = 42161L

    override fun finalizedBlock(): JsonNode = mapper.readTree("""{"number":"0x100","hash":"0xhead","timestamp":"0x65f00000"}""")

    override fun blockByNumber(number: Long): JsonNode? = null

    override fun transferLogs(
        fromBlock: Long,
        toBlock: Long,
        addresses: List<String>,
        fromSide: Boolean,
    ): JsonNode = mapper.createArrayNode()

    override fun nativeBalance(address: String): BigInteger {
        if (address in failing) throw EvmException("boom", 500)
        return native[address] ?: BigInteger.ZERO
    }

    override fun balanceOf(
        contract: String,
        address: String,
    ): BigInteger? {
        if (address in failing) throw EvmException("boom", 500)
        return token[contract to address]
    }

    override fun decimals(contract: String): Int? = null

    override fun totalSupply(contract: String): BigInteger? = null

    override fun transactionCount(address: String): Long = 0
}

private class BalanceFakeStore(
    vararg wallets: String,
) : OnchainStagingStore {
    val watched = wallets.map { WatchSource(CHAIN_ARBITRUM_ONE, it, null, null) }
    var contracts: List<TokenContract> = emptyList()
    val snapshots = mutableListOf<OnchainBalance>()

    override fun activeWatchedAddresses(chain: String) = watched.filter { it.chain == chain }

    override fun newestSlot(
        chain: String,
        wallet: String,
    ): Long? = null

    override fun watchedTokenAccounts(chain: String): Map<String, String> = emptyMap()

    override fun newestStagedSlot(chain: String): Long? = null

    override fun tokenContracts(chain: String): List<TokenContract> = contracts

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
    ) = emptyList<OnchainBalance>()

    override fun insertEvidence(
        evidence: List<OnchainEvidence>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ) = evidence.size
}

private fun collector(
    rpc: BalanceFakeRpc,
    store: BalanceFakeStore,
) = EvmBalanceCollector(
    store,
    rpc,
    EvmConfig("https://rpc.example", CHAIN_ARBITRUM_ONE, 42161L),
)

class EvmBalanceCollectorTest {
    @Test
    fun `native ETH and registered token balances snapshot at the finalized block`() {
        val rpc =
            BalanceFakeRpc().apply {
                native[EB_WALLET] = BigInteger("500000000000000000")
                token[EB_USDC to EB_WALLET] = BigInteger("250000000")
                token[EB_USDCE to EB_WALLET] = BigInteger("40000000")
            }
        val store =
            BalanceFakeStore(EB_WALLET).apply {
                contracts = listOf(TokenContract(EB_USDC, 6), TokenContract(EB_USDCE, 6))
            }
        val report = collector(rpc, store).collect()

        assertEquals(3, report.snapshotsInserted)
        assertEquals(1, report.walletsSeen)

        val native = store.snapshots.single { it.mintAddress == null }
        assertEquals(BigInteger("500000000000000000"), native.amountRaw)
        assertEquals(18, native.decimals)
        assertEquals(BalanceSource.RPC, native.source)
        assertEquals(256L, native.slot) // 0x100
        assertEquals(CHAIN_ARBITRUM_ONE, native.chain)
        assertEquals("rpc-$CHAIN_ARBITRUM_ONE", native.sourceSystem)

        // USDC and USDC.e stay two instruments — keyed by contract, never by symbol.
        assertEquals(
            setOf(EB_USDC, EB_USDCE),
            store.snapshots
                .filter { it.mintAddress != null }
                .map { it.mintAddress }
                .toSet(),
        )
    }

    @Test
    fun `zero balances are skipped — a sparse table is the honest shape`() {
        val rpc = BalanceFakeRpc().apply { token[EB_USDC to EB_WALLET] = BigInteger.ZERO }
        val store = BalanceFakeStore(EB_WALLET).apply { contracts = listOf(TokenContract(EB_USDC, 6)) }

        val report = collector(rpc, store).collect()

        assertEquals(0, report.snapshotsInserted)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun `a contract call that reverts counts as skipped, not failed`() {
        val rpc = BalanceFakeRpc().apply { native[EB_WALLET] = BigInteger.ONE }
        val store = BalanceFakeStore(EB_WALLET).apply { contracts = listOf(TokenContract(EB_USDC, 6)) }
        // EB_USDC not in rpc.token -> balanceOf returns null

        val report = collector(rpc, store).collect()

        assertEquals(1, report.skippedCalls)
        assertEquals(1, report.snapshotsInserted) // the native row still lands
        assertTrue(report.failedWallets.isEmpty())
    }

    @Test
    fun `a failing wallet lands in failedWallets and the run continues`() {
        val rpc =
            BalanceFakeRpc().apply {
                failing += EB_WALLET
                token[EB_USDC to EB_WALLET2] = BigInteger("9")
            }
        val store = BalanceFakeStore(EB_WALLET, EB_WALLET2).apply { contracts = listOf(TokenContract(EB_USDC, 6)) }

        val report = collector(rpc, store).collect()

        assertEquals(listOf(EB_WALLET), report.failedWallets)
        assertEquals(1, report.snapshotsInserted)
        assertEquals(EB_WALLET2, store.snapshots.single().wallet)
    }

    @Test
    fun `no watched wallets means no rpc calls beyond nothing`() {
        val rpc = BalanceFakeRpc()
        val report = collector(rpc, BalanceFakeStore()).collect()
        assertEquals(0, report.walletsSeen)
        assertEquals(0, report.snapshotsInserted)
    }
}
