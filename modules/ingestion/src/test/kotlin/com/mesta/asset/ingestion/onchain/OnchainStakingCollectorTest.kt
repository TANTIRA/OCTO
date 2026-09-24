package com.mesta.asset.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.helius.HeliusException
import com.mesta.asset.ingestion.onchain.helius.HeliusRpcApi
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val SC_WALLET = "7VVA" + "F".repeat(39)
private val SC_WALLET2 = "8WWB" + "G".repeat(39)
private val SC_STAKE = "StakeAcct" + "D".repeat(34)
private val SC_NOW = Instant.parse("2025-06-01T00:00:00Z")

private class StakingStubRpc(
    private val accounts: JsonNode,
    private val rewards: JsonNode,
    private val failOn: MutableSet<String> = mutableSetOf(),
) : HeliusRpcApi {
    val stakeCalls = mutableListOf<String>()
    var rewardEpochs = mutableListOf<Long?>()

    override fun signaturesForAddress(
        address: String,
        limit: Int,
        before: String?,
        until: String?,
    ): JsonNode = throw UnsupportedOperationException()

    override fun transaction(signature: String): JsonNode? = null

    override fun balance(address: String) = 0L

    override fun tokenAccountsByOwner(address: String): JsonNode = ObjectMapper().createArrayNode()

    override fun stakeAccounts(address: String): JsonNode {
        stakeCalls += address
        if (address in failOn) throw HeliusException("rpc boom", 500)
        return accounts
    }

    override fun inflationReward(
        addresses: List<String>,
        epoch: Long?,
    ): JsonNode {
        rewardEpochs += epoch
        return rewards
    }

    override fun blockTime(slot: Long): Long? = 1_751_000_000


    override fun tokenSupply(mint: String) = ObjectMapper().createObjectNode()

    override fun tokenLargestAccounts(mint: String) = ObjectMapper().createArrayNode()
}

private class StakingFakeStore : OnchainStagingStore {
    var watched: List<WatchSource> = emptyList()
    val transfers = mutableListOf<OnchainTransfer>()
    val snapshots = mutableListOf<OnchainBalance>()
    val actors = mutableListOf<String>()

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
        this.transfers += transfers
        actors += actor
        return transfers.size
    }

    override fun insertSnapshots(
        balances: List<OnchainBalance>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        snapshots += balances
        actors += actor
        return balances.size
    }

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

class OnchainStakingCollectorTest {
    private val mapper = ObjectMapper()

    private fun accountsJson(vararg pubkeys: Pair<String, String>) =
        mapper.readTree(
            pubkeys.joinToString(",", "[", "]") { (pk, stake) ->
                """{"pubkey":"$pk","account":{"data":{"parsed":{"info":{"stake":{"delegation":{"voter":"Vote1","stake":"$stake","activationEpoch":"690","deactivationEpoch":"18446744073709551615"}}}}}}}"""
            },
        )

    private fun rewardsJson(vararg entries: String) = mapper.readTree(entries.joinToString(",", "[", "]"))

    @Test
    fun `each watched wallet stages rewards and a delegated-stake snapshot`() {
        val rpc =
            StakingStubRpc(
                accounts = accountsJson(SC_STAKE to "42000000000"),
                rewards = rewardsJson("""{"epoch":700,"effectiveSlot":305587200,"amount":1234,"postBalance":42000001234,"commission":5}"""),
            )
        val store =
            StakingFakeStore().apply {
                watched =
                    listOf(
                        WatchSource(CHAIN_SOLANA, SC_WALLET, null, null),
                        WatchSource(CHAIN_SOLANA, SC_WALLET2, null, null),
                    )
            }
        val report = OnchainStakingCollector(store, rpc).collect(epoch = 700, asOf = SC_NOW)

        assertEquals(2, report.walletsSeen)
        assertEquals(listOf(SC_WALLET, SC_WALLET2), rpc.stakeCalls)
        assertEquals(listOf<Long?>(700L, 700L), rpc.rewardEpochs)
        assertEquals(2, report.rewardRowsInserted)
        assertEquals(2, report.snapshotRowsInserted)
        assertEquals("solana:700:$SC_STAKE", store.transfers.first().externalId)
        assertEquals(SC_STAKE, store.snapshots.first().tokenAccount)
        assertEquals(BalanceSource.RPC, store.snapshots.first().source)
        assertTrue(store.actors.all { it == OnchainStakingCollector.ACTOR })
    }

    @Test
    fun `a wallet with no stake accounts stages nothing`() {
        val rpc = StakingStubRpc(accounts = mapper.createArrayNode(), rewards = mapper.createArrayNode())
        val store = StakingFakeStore().apply { watched = listOf(WatchSource(CHAIN_SOLANA, SC_WALLET, null, null)) }

        val report = OnchainStakingCollector(store, rpc).collect(asOf = SC_NOW)

        assertEquals(0, report.rewardRowsInserted)
        assertTrue(store.transfers.isEmpty() && store.snapshots.isEmpty())
    }

    @Test
    fun `a failing wallet is reported and the run continues`() {
        val rpc =
            StakingStubRpc(
                accounts = accountsJson(SC_STAKE to "42000000000"),
                rewards = rewardsJson("""{"epoch":700,"effectiveSlot":305587200,"amount":10,"postBalance":10,"commission":5}"""),
                failOn = mutableSetOf(SC_WALLET),
            )
        val store =
            StakingFakeStore().apply {
                watched = listOf(WatchSource(CHAIN_SOLANA, SC_WALLET, null, null), WatchSource(CHAIN_SOLANA, SC_WALLET2, null, null))
            }

        val report = OnchainStakingCollector(store, rpc).collect(asOf = SC_NOW)

        assertEquals(listOf(SC_WALLET), report.failedWallets)
        assertEquals(1, report.rewardRowsInserted)
    }
}
