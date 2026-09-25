package com.mesta.asset.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.helius.HeliusRpcApi
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val EV_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
private val EV_TREASURY = "7VVA" + "I".repeat(39)
private val EV_NOW = Instant.parse("2025-06-01T00:00:00Z")

private class EvidenceStubRpc : HeliusRpcApi {
    private val mapper = ObjectMapper()

    override fun signaturesForAddress(
        address: String,
        limit: Int,
        before: String?,
        until: String?,
    ): JsonNode = mapper.readTree((1..50).joinToString(",", "[", "]") { """{"signature":"s$it"}""" })

    override fun transaction(signature: String): JsonNode? = null

    override fun balance(address: String) = 6_000_000_000_000L

    override fun tokenAccountsByOwner(address: String): JsonNode = mapper.createArrayNode()

    override fun stakeAccounts(address: String): JsonNode = mapper.createArrayNode()

    override fun inflationReward(
        addresses: List<String>,
        epoch: Long?,
    ): JsonNode = mapper.createArrayNode()

    override fun blockTime(slot: Long): Long? = null

    override fun tokenSupply(mint: String): JsonNode =
        mapper.readTree("""{"amount":"1000000000000","decimals":6,"uiAmount":1000000.0,"uiAmountString":"1000000"}""")

    override fun tokenLargestAccounts(mint: String): JsonNode =
        mapper.readTree(
            """[
              {"amount":"500000000000","decimals":6,"uiAmount":500000.0},
              {"amount":"220000000000","decimals":6,"uiAmount":220000.0}
            ]""",
        )
}

class OnchainEvidenceAdapterTest {
    private val adapter = OnchainEvidenceAdapter(EvidenceStubRpc())

    @Test
    fun `a mint subject yields supply and concentration evidence`() {
        val evidence = adapter.gather(EvidenceSubject(claimRef = "c1", mintAddress = EV_MINT), asOf = EV_NOW)

        assertEquals(setOf(EvidenceKind.TOKEN_SUPPLY, EvidenceKind.HOLDER_CONCENTRATION), evidence.map { it.kind }.toSet())
        val supply = evidence.first { it.kind == EvidenceKind.TOKEN_SUPPLY }
        assertEquals(BigDecimal("1000000000000"), supply.observedNumeric)
        assertEquals("solana:token-supply:$EV_MINT:${EV_NOW.epochSecond}:1000000000000", supply.externalId)

        // Top-2 accounts hold 72% of supply.
        val concentration = evidence.first { it.kind == EvidenceKind.HOLDER_CONCENTRATION }
        assertEquals(0, BigDecimal("0.720000").compareTo(concentration.observedNumeric!!))
    }

    @Test
    fun `a treasury subject yields balance and activity evidence`() {
        val evidence = adapter.gather(EvidenceSubject(claimRef = "c2", treasuryAddress = EV_TREASURY), asOf = EV_NOW)

        assertEquals(setOf(EvidenceKind.TREASURY_BALANCE, EvidenceKind.ACCOUNT_ACTIVITY), evidence.map { it.kind }.toSet())
        assertEquals(BigDecimal("6000000000000"), evidence.first { it.kind == EvidenceKind.TREASURY_BALANCE }.observedNumeric)
        assertEquals(BigDecimal("50"), evidence.first { it.kind == EvidenceKind.ACCOUNT_ACTIVITY }.observedNumeric)
    }

    @Test
    fun `claim text never enters a record — claimRef stays opaque`() {
        val evidence =
            adapter.gather(
                EvidenceSubject(claimRef = "claim-xyz-only-a-ref", mintAddress = EV_MINT),
                asOf = EV_NOW,
            )

        assertTrue(evidence.all { it.claimRef == "claim-xyz-only-a-ref" })
        assertTrue(evidence.all { it.chain == CHAIN_SOLANA })
    }
}
