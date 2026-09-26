package com.mesta.asset.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SW_WALLET = "0x1111111111111111111111111111111111111111"
private const val SW_OTHER = "0x9999999999999999999999999999999999999999"
private const val SW_CONTRACT = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
private const val SW_CHAIN_ID = 42161L

private class ScanFakeRpc(
    private val head: Long = 25,
    private val chainId: Long = SW_CHAIN_ID,
) : EvmRpcApi {
    /** (from, to, fromSide) -> logs; missing key returns an empty page. */
    var logPages = mutableMapOf<Triple<Long, Long, Boolean>, JsonNode>()
    val logCalls = mutableListOf<Triple<Long, Long, Boolean>>()
    /** Ranges that throw an RPC-level error once — the provider window cap. */
    val failOnce = mutableSetOf<Pair<Long, Long>>()
    var decimals = mutableMapOf<String, Int>()
    var blocks = mutableMapOf<Long, JsonNode>()
    private val mapper = ObjectMapper()

    override fun chainId(): Long = chainId

    override fun finalizedBlock(): JsonNode =
        mapper.readTree("""{"number":"0x${head.toString(16)}","hash":"0xhead","timestamp":"0x66000000"}""")

    override fun blockByNumber(number: Long): JsonNode? =
        blocks.getOrPut(number) {
            mapper.readTree("""{"number":"0x${number.toString(16)}","timestamp":"0x65f00000"}""")
        }

    override fun transferLogs(
        fromBlock: Long,
        toBlock: Long,
        addresses: List<String>,
        fromSide: Boolean,
    ): JsonNode {
        val key = Triple(fromBlock, toBlock, fromSide)
        logCalls += key
        if (failOnce.remove(fromBlock to toBlock)) {
            throw EvmException("query returned more than 10000 results")
        }
        return logPages[key] ?: mapper.createArrayNode()
    }

    override fun nativeBalance(address: String): BigInteger = BigInteger.ZERO

    override fun balanceOf(
        contract: String,
        address: String,
    ): BigInteger? = null

    override fun decimals(contract: String): Int? = decimals[contract]

    override fun totalSupply(contract: String): BigInteger? = null

    override fun transactionCount(address: String): Long = 0
}

private class ScanFakeStore(
    vararg wallets: String,
) : OnchainStagingStore {
    val watched = wallets.map { WatchSource(CHAIN_ARBITRUM_ONE, it, null, null) }
    var cursor: Long? = null
    var contracts: List<TokenContract> = emptyList()
    val transfers = mutableListOf<OnchainTransfer>()

    override fun activeWatchedAddresses(chain: String) = watched.filter { it.chain == chain }

    override fun newestSignature(
        chain: String,
        wallet: String,
    ) = null

    override fun newestStagedSlot(chain: String): Long? = cursor

    override fun tokenContracts(chain: String): List<TokenContract> = contracts

    override fun insertTransfers(
        transfers: List<OnchainTransfer>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        this.transfers += transfers
        return transfers.size
    }

    override fun insertSnapshots(
        balances: List<OnchainBalance>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ) = 0

    override fun latestSnapshots(
        chain: String,
        wallet: String,
    ): List<OnchainBalance> = emptyList()

    override fun insertEvidence(
        evidence: List<OnchainEvidence>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ) = evidence.size
}

private fun transferLog(
    from: String,
    to: String,
    amount: Long = 250_000_000,
    txHash: String = "0xtx",
    logIndex: Int = 0,
    block: Long,
    contract: String = SW_CONTRACT,
): JsonNode =
    ObjectMapper().readTree(
        """
        {"address":"$contract",
         "topics":["${EvmRpcClient.TRANSFER_TOPIC}","0x${"0".repeat(24)}${from.removePrefix("0x")}","0x${"0".repeat(24)}${to.removePrefix("0x")}"],
         "data":"0x${amount.toString(16)}",
         "blockNumber":"0x${block.toString(16)}",
         "transactionHash":"$txHash",
         "logIndex":"0x${logIndex.toString(16)}",
         "blockHash":"0xblock$block",
         "removed":false}
        """.trimIndent(),
    )

private fun logs(vararg entries: JsonNode): JsonNode =
    ObjectMapper().createArrayNode().apply { entries.forEach { add(it) } }

private fun service(
    rpc: EvmRpcApi,
    store: ScanFakeStore,
    window: Long = 10,
) = EvmScanService(
    rpc,
    EvmTransferNormalizer(),
    store,
    EvmConfig("https://rpc.example", CHAIN_ARBITRUM_ONE, SW_CHAIN_ID, maxBlockWindow = window),
)

class EvmScanServiceTest {
    @Test
    fun `no watched wallets scans nothing beyond the head probe`() {
        val rpc = ScanFakeRpc()
        val report = service(rpc, ScanFakeStore()).scan()
        assertEquals(0, report.windowsScanned)
        assertTrue(rpc.logCalls.isEmpty())
    }

    @Test
    fun `the scan starts after the staged cursor and chunks by window`() {
        val rpc = ScanFakeRpc(head = 25)
        val store = ScanFakeStore(SW_WALLET).apply { cursor = 5 }
        service(rpc, store).scan()

        assertEquals(
            listOf(
                Triple(6L, 15L, true),
                Triple(6L, 15L, false),
                Triple(16L, 25L, true),
                Triple(16L, 25L, false),
            ),
            rpc.logCalls,
        )
    }

    @Test
    fun `a cold scan starts at startBlock when configured`() {
        val rpc = ScanFakeRpc(head = 12)
        val store = ScanFakeStore(SW_WALLET)
        EvmScanService(
            rpc,
            EvmTransferNormalizer(),
            store,
            EvmConfig("https://rpc.example", CHAIN_ARBITRUM_ONE, SW_CHAIN_ID, startBlock = 10, maxBlockWindow = 10),
        ).scan()

        assertEquals(Triple(10L, 12L, true), rpc.logCalls.first())
    }

    @Test
    fun `logs stage one leg per watched wallet and dedupe across the two queries`() {
        val rpc = ScanFakeRpc(head = 10)
        val log = transferLog(from = SW_OTHER, to = SW_WALLET, block = 8)
        // A to==from watched wallet appears in BOTH topic filters; the log is still one fact.
        rpc.logPages[Triple(0L, 10L, false)] = logs(log)
        rpc.logPages[Triple(0L, 10L, true)] = logs(log)
        val store = ScanFakeStore(SW_WALLET).apply { contracts = listOf(TokenContract(SW_CONTRACT, 6)) }

        val report = service(rpc, store).scan()

        assertEquals(1, report.logsSeen)
        assertEquals(1, report.legsStaged)
        assertEquals("$CHAIN_ARBITRUM_ONE:0xtx:$SW_WALLET:log:0", store.transfers.single().externalId)
        assertEquals("rpc-$CHAIN_ARBITRUM_ONE", store.transfers.single().sourceSystem)
    }

    @Test
    fun `registered contracts resolve decimals from the registry, unknown ones skip`() {
        val rpc = ScanFakeRpc(head = 10)
        rpc.logPages[Triple(0L, 10L, false)] =
            logs(
                transferLog(from = SW_OTHER, to = SW_WALLET, txHash = "0xt1", block = 8),
                transferLog(from = SW_OTHER, to = SW_WALLET, txHash = "0xt2", block = 9, contract = "0xunknowncontract0000000000000000000000"),
            )
        val store = ScanFakeStore(SW_WALLET).apply { contracts = listOf(TokenContract(SW_CONTRACT, 6)) }

        val report = service(rpc, store).scan()

        assertEquals(1, report.legsStaged)
        assertEquals(listOf("0xunknowncontract0000000000000000000000"), report.skippedContracts)
    }

    @Test
    fun `unregistered contracts fall back to a decimals call`() {
        val rpc = ScanFakeRpc(head = 10).apply { decimals[SW_CONTRACT] = 18 }
        rpc.logPages[Triple(0L, 10L, false)] = logs(transferLog(from = SW_OTHER, to = SW_WALLET, block = 8))
        val store = ScanFakeStore(SW_WALLET)

        val report = service(rpc, store).scan()

        assertEquals(1, report.legsStaged)
        assertEquals(18, store.transfers.single().decimals)
    }

    @Test
    fun `a provider range error shrinks the window and the run completes`() {
        val rpc = ScanFakeRpc(head = 25)
        rpc.failOnce += 10L to 19L // first attempt at the full second window
        rpc.logPages[Triple(10L, 14L, false)] = logs(transferLog(from = SW_OTHER, to = SW_WALLET, block = 12))
        val store = ScanFakeStore(SW_WALLET).apply { contracts = listOf(TokenContract(SW_CONTRACT, 6)) }

        val report = service(rpc, store).scan()

        assertEquals(1, report.windowShrinks)
        // [0-9] then, after the shrink, [10-14] [15-19] [20-24] [25-25]
        assertEquals(5, report.windowsScanned)
        assertEquals(1, report.legsStaged)
    }

    @Test
    fun `a transport failure propagates — an outage is not a range error`() {
        val delegate = ScanFakeRpc(head = 10)
        val throwing =
            object : EvmRpcApi by delegate {
                override fun transferLogs(
                    fromBlock: Long,
                    toBlock: Long,
                    addresses: List<String>,
                    fromSide: Boolean,
                ): JsonNode = throw EvmException("http 503", 503)
            }
        assertFailsWith<EvmException> { service(throwing, ScanFakeStore(SW_WALLET)).scan() }
    }

    @Test
    fun `a chain-id mismatch refuses to scan`() {
        val rpc = ScanFakeRpc(chainId = 1L)
        assertFailsWith<IllegalArgumentException> { service(rpc, ScanFakeStore(SW_WALLET)).scan() }
    }
}
