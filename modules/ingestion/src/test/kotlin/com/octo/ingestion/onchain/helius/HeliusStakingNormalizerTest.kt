package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.TransferDirection
import com.mesta.asset.ingestion.onchain.TransferKind
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val ST_WALLET = "7VVA" + "E".repeat(39)
private val STAKE_A = "StakeAcct" + "A".repeat(34)
private val STAKE_B = "StakeAcct" + "B".repeat(34)
private val OBSERVED = Instant.parse("2025-06-01T00:00:00Z")

private val accountsResponse =
    """
    [{
      "pubkey": "$STAKE_A",
      "account": {"data": {"parsed": {"info": {
        "stake": {"delegation": {"voter": "Vote111", "stake": "42000000000", "activationEpoch": "690", "deactivationEpoch": "18446744073709551615"}}
      }}}}
    }]
    """.trimIndent()

private val rewardsResponse =
    """
    [
      {"epoch": 700, "effectiveSlot": 305587200, "amount": 123456789, "postBalance": 42123456789, "commission": 5},
      null,
      {"epoch": 700, "effectiveSlot": 305587200, "amount": 50, "postBalance": 1050, "commission": 5}
    ]
    """.trimIndent()

class HeliusStakingNormalizerTest {
    private val mapper = ObjectMapper()
    private val normalizer = HeliusStakingNormalizer()

    @Test
    fun `parsed program accounts become stake-account state`() {
        val accounts = normalizer.stakeAccounts(mapper.readTree(accountsResponse), ST_WALLET).single()

        assertEquals(STAKE_A, accounts.stakeAccount)
        assertEquals(ST_WALLET, accounts.wallet)
        assertEquals("Vote111", accounts.voter)
        assertEquals(690, accounts.activationEpoch)
        assertEquals(BigInteger("42000000000"), accounts.delegatedStakeRaw)
    }

    @Test
    fun `rewards map one transfer per rewarded account, keyed by epoch and stake account`() {
        val rewards =
            normalizer.rewards(
                mapper.readTree(rewardsResponse),
                ST_WALLET,
                listOf(STAKE_A, STAKE_B, "StakeAcct" + "C".repeat(34)),
                blockTimeOf = { 1_751_000_000 },
                observedAt = OBSERVED,
            )

        assertEquals(2, rewards.size)
        val first = rewards[0]
        assertEquals("solana:700:$STAKE_A", first.externalId)
        assertEquals("reward:700:$STAKE_A", first.signature)
        assertEquals(305587200, first.slot)
        assertEquals(Instant.ofEpochSecond(1_751_000_000), first.blockTime)
        assertEquals(BigInteger("123456789"), first.amountRaw)
        assertEquals(9, first.decimals)
        assertNull(first.mintAddress)
        assertEquals(STAKE_A, first.tokenAccount)
        assertEquals(TransferDirection.IN, first.direction)
        assertEquals(TransferKind.STAKING_REWARD, first.transferKind)
    }

    @Test
    fun `missing blockTime falls back to the observation instant`() {
        val rewards =
            normalizer.rewards(
                mapper.readTree(rewardsResponse),
                ST_WALLET,
                listOf(STAKE_A, STAKE_B, "StakeAcct" + "C".repeat(34)),
                blockTimeOf = { null },
                observedAt = OBSERVED,
            )

        assertEquals(OBSERVED, rewards.first().blockTime)
    }

    @Test
    fun `a wallet-matched account with no pubkey or no amount yields no fact`() {
        val rewards =
            normalizer.rewards(
                mapper.readTree("""[{"epoch": 1, "effectiveSlot": 1, "amount": "oops"}]"""),
                ST_WALLET,
                listOf(STAKE_A),
                blockTimeOf = { null },
                observedAt = OBSERVED,
            )

        assertEquals(0, rewards.size)
    }
}
