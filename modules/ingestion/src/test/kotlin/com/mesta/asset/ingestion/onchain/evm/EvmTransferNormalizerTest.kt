package com.mesta.asset.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.CHAIN_ARBITRUM_ONE
import com.mesta.asset.ingestion.onchain.TransferDirection
import com.mesta.asset.ingestion.onchain.TransferKind
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SOURCE = "rpc-arbitrum-one"
private const val WATCHED = "0x1111111111111111111111111111111111111111"
private const val WATCHED2 = "0x2222222222222222222222222222222222222222"
private const val OTHER = "0x9999999999999999999999999999999999999999"
private const val CONTRACT = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
private val BLOCK_TIME = Instant.parse("2026-03-01T12:00:00Z")

private fun log(
    from: String,
    to: String,
    amount: BigInteger = BigInteger("250000000"),
    txHash: String = "0xtxabc",
    logIndex: Int = 7,
    block: Long = 12345,
    contract: String = CONTRACT,
    removed: Boolean = false,
): JsonNode =
    ObjectMapper().readTree(
        """
        {"address":"$contract",
         "topics":["${EvmRpcClient.TRANSFER_TOPIC}","0x${"0".repeat(24)}${from.removePrefix("0x")}","0x${"0".repeat(24)}${to.removePrefix("0x")}"],
         "data":"0x${amount.toString(16)}",
         "blockNumber":"0x${block.toString(16)}",
         "transactionHash":"$txHash",
         "logIndex":"0x${logIndex.toString(16)}",
         "blockHash":"0xblockhash",
         "removed":$removed}
        """.trimIndent(),
    )

class EvmTransferNormalizerTest {
    private val normalizer = EvmTransferNormalizer()

    private fun legs(
        node: JsonNode,
        vararg wallets: String,
    ) = normalizer.normalize(node, wallets.toSet(), 6, BLOCK_TIME, CHAIN_ARBITRUM_ONE, SOURCE)

    @Test
    fun `an incoming transfer stages one inbound leg`() {
        val leg = legs(log(from = OTHER, to = WATCHED), WATCHED).single()
        assertEquals(TransferDirection.IN, leg.direction)
        assertEquals(TransferKind.TRANSFER_IN, leg.transferKind)
        assertEquals(OTHER, leg.counterparty)
        assertEquals(CONTRACT, leg.mintAddress)
        assertEquals(BigInteger("250000000"), leg.amountRaw)
        assertEquals(6, leg.decimals)
        assertEquals(12345L, leg.slot)
        assertEquals("0xtxabc", leg.signature)
        assertEquals("0xblockhash", leg.blockHash)
        assertEquals(BLOCK_TIME, leg.blockTime)
        assertEquals(CHAIN_ARBITRUM_ONE, leg.chain)
        assertEquals(SOURCE, leg.sourceSystem)
        assertEquals("$CHAIN_ARBITRUM_ONE:0xtxabc:$WATCHED:log:7", leg.externalId)
    }

    @Test
    fun `an outgoing transfer stages one outbound leg`() {
        val leg = legs(log(from = WATCHED, to = OTHER), WATCHED).single()
        assertEquals(TransferDirection.OUT, leg.direction)
        assertEquals(TransferKind.TRANSFER_OUT, leg.transferKind)
        assertEquals(OTHER, leg.counterparty)
    }

    @Test
    fun `a transfer between two watched wallets stages both legs`() {
        val result = legs(log(from = WATCHED, to = WATCHED2), WATCHED, WATCHED2)
        assertEquals(2, result.size)
        assertEquals(
            setOf(
                "$CHAIN_ARBITRUM_ONE:0xtxabc:$WATCHED:log:7",
                "$CHAIN_ARBITRUM_ONE:0xtxabc:$WATCHED2:log:7",
            ),
            result.map { it.externalId }.toSet(),
        )
        assertEquals(TransferDirection.OUT, result.first { it.wallet == WATCHED }.direction)
        assertEquals(TransferDirection.IN, result.first { it.wallet == WATCHED2 }.direction)
    }

    @Test
    fun `a mint from the zero address is inbound mint`() {
        val leg = legs(log(from = EvmTransferNormalizer.ZERO_ADDRESS, to = WATCHED), WATCHED).single()
        assertEquals(TransferKind.MINT, leg.transferKind)
        assertEquals(TransferDirection.IN, leg.direction)
        assertEquals(EvmTransferNormalizer.ZERO_ADDRESS, leg.counterparty)
    }

    @Test
    fun `a burn to the zero address is outbound burn`() {
        val leg = legs(log(from = WATCHED, to = EvmTransferNormalizer.ZERO_ADDRESS), WATCHED).single()
        assertEquals(TransferKind.BURN, leg.transferKind)
        assertEquals(TransferDirection.OUT, leg.direction)
    }

    @Test
    fun `a self transfer stages nothing — it nets zero like the Solana zero-delta skip`() {
        assertTrue(legs(log(from = WATCHED, to = WATCHED), WATCHED).isEmpty())
    }

    @Test
    fun `zero-value spam stages nothing`() {
        assertTrue(legs(log(from = OTHER, to = WATCHED, amount = BigInteger.ZERO), WATCHED).isEmpty())
    }

    @Test
    fun `a removed log stages nothing`() {
        assertTrue(legs(log(from = OTHER, to = WATCHED, removed = true), WATCHED).isEmpty())
    }

    @Test
    fun `a transfer touching no watched wallet stages nothing`() {
        assertTrue(legs(log(from = OTHER, to = "0x3333333333333333333333333333333333333333"), WATCHED).isEmpty())
    }

    @Test
    fun `contract addresses normalize to lowercase`() {
        val noisy = log(from = OTHER, to = WATCHED, contract = "0xAF88D065E77C8CC2239327C5EDB3A432268E5831")
        assertEquals(CONTRACT, legs(noisy, WATCHED).single().mintAddress)
    }

    @Test
    fun `malformed logs do not throw — they produce nothing`() {
        val noTopics = ObjectMapper().readTree("""{"topics":[],"data":"0x1"}""")
        assertTrue(legs(noTopics, WATCHED).isEmpty())
        val badData = ObjectMapper().readTree(
            """{"topics":["${EvmRpcClient.TRANSFER_TOPIC}","0x${"0".repeat(24)}${OTHER.removePrefix("0x")}","0x${"0".repeat(24)}${WATCHED.removePrefix("0x")}"],"data":"0x","removed":false}""",
        )
        assertTrue(legs(badData, WATCHED).isEmpty())
    }
}
