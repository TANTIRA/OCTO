package com.mesta.asset.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.CHAIN_ARBITRUM_ONE
import com.mesta.asset.ingestion.onchain.EvidenceKind
import com.mesta.asset.ingestion.onchain.EvidenceSubject
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EE_MINT = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
private const val EE_TREASURY = "0x1111111111111111111111111111111111111111"
private val EE_AS_OF = Instant.parse("2026-03-02T09:00:00Z")

private class EvidenceFakeRpc : EvmRpcApi {
    var supply: BigInteger? = null
    var tokenBalance: BigInteger? = null
    var native: BigInteger = BigInteger.ZERO
    var nonce: Long = 0
    private val mapper = ObjectMapper()

    override fun chainId() = 42161L

    override fun finalizedBlock(): JsonNode = mapper.createObjectNode()

    override fun blockByNumber(number: Long): JsonNode? = null

    override fun transferLogs(
        fromBlock: Long,
        toBlock: Long,
        addresses: List<String>,
        fromSide: Boolean,
    ): JsonNode = mapper.createArrayNode()

    override fun nativeBalance(address: String): BigInteger = native

    override fun balanceOf(
        contract: String,
        address: String,
    ): BigInteger? = tokenBalance

    override fun decimals(contract: String): Int? = null

    override fun totalSupply(contract: String): BigInteger? = supply

    override fun transactionCount(address: String): Long = nonce
}

private fun adapter(rpc: EvidenceFakeRpc) =
    EvmEvidenceAdapter(rpc, EvmConfig("https://rpc.example", CHAIN_ARBITRUM_ONE, 42161L))

class EvmEvidenceAdapterTest {
    @Test
    fun `token supply and treasury balance record as finalized observations`() {
        val rpc =
            EvidenceFakeRpc().apply {
                supply = BigInteger("40000000000000")
                tokenBalance = BigInteger("250000000")
                nonce = 17
            }
        val subject = EvidenceSubject(claimRef = "claim-1", mintAddress = EE_MINT, treasuryAddress = EE_TREASURY)

        val rows = adapter(rpc).gather(subject, asOf = EE_AS_OF)

        assertEquals(3, rows.size)
        val supply = rows.single { it.kind == EvidenceKind.TOKEN_SUPPLY }
        assertEquals(BigDecimal("40000000000000"), supply.observedNumeric)
        assertEquals(EE_MINT, supply.subjectAddress)
        assertEquals(CHAIN_ARBITRUM_ONE, supply.chain)
        assertEquals("rpc-$CHAIN_ARBITRUM_ONE", supply.sourceSystem)

        val treasury = rows.single { it.kind == EvidenceKind.TREASURY_BALANCE }
        assertEquals(BigDecimal("250000000"), treasury.observedNumeric) // raw base units, not ETH
        val activity = rows.single { it.kind == EvidenceKind.ACCOUNT_ACTIVITY }
        assertEquals(BigDecimal("17"), activity.observedNumeric)
    }

    @Test
    fun `a treasury without a mint reports native ETH in wei`() {
        val rpc = EvidenceFakeRpc().apply { native = BigInteger("1500000000000000000") }
        val subject = EvidenceSubject(claimRef = "claim-2", treasuryAddress = EE_TREASURY)

        val rows = adapter(rpc).gather(subject, asOf = EE_AS_OF)

        val treasury = rows.single { it.kind == EvidenceKind.TREASURY_BALANCE }
        assertEquals(BigDecimal("1500000000000000000"), treasury.observedNumeric)
        assertEquals("1500000000000000000", treasury.payload["wei"].asText())
    }

    @Test
    fun `holder-concentration is never fabricated — the verifier sees missing evidence`() {
        val rpc =
            EvidenceFakeRpc().apply {
                supply = BigInteger("1000")
            }
        val subject = EvidenceSubject(claimRef = "claim-3", mintAddress = EE_MINT)

        val rows = adapter(rpc).gather(subject, asOf = EE_AS_OF)

        assertTrue(rows.none { it.kind == EvidenceKind.HOLDER_CONCENTRATION })
    }

    @Test
    fun `a reverting balanceOf produces no treasury row — unknown is not zero`() {
        val rpc = EvidenceFakeRpc() // tokenBalance null = revert
        val subject = EvidenceSubject(claimRef = "claim-4", mintAddress = EE_MINT, treasuryAddress = EE_TREASURY)

        val rows = adapter(rpc).gather(subject, asOf = EE_AS_OF)

        assertTrue(rows.none { it.kind == EvidenceKind.TREASURY_BALANCE })
        assertTrue(rows.any { it.kind == EvidenceKind.ACCOUNT_ACTIVITY })
    }

    @Test
    fun `an unimplemented totalSupply produces no supply row`() {
        val rpc = EvidenceFakeRpc() // supply null
        val subject = EvidenceSubject(claimRef = "claim-5", mintAddress = EE_MINT)

        assertTrue(adapter(rpc).gather(subject, asOf = EE_AS_OF).isEmpty())
    }
}
