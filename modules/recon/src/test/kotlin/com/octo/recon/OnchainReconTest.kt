package com.mesta.asset.recon

import com.mesta.asset.iborcore.InstrumentKey
import com.mesta.asset.iborcore.TokenPosition
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SOL = UUID.randomUUID()
private val BONK = UUID.randomUUID()
private const val BONK_MINT = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"
private const val WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
private val AS_OF = Instant.parse("2025-06-01T00:00:00Z")

private val instruments =
    mapOf(
        InstrumentKey("solana", null) to SOL,
        InstrumentKey("solana", BONK_MINT) to BONK,
    )

private fun position(
    instrument: UUID = SOL,
    net: String,
    unclassified: String = "0",
) = TokenPosition(
    instrumentId = instrument,
    wallet = WALLET,
    decimals = 9,
    netRaw = BigInteger(net),
    inboundRaw = BigInteger.ZERO,
    outboundRaw = BigInteger.ZERO,
    unclassifiedRaw = BigInteger(unclassified),
)

private fun observed(
    amount: String,
    mint: String? = null,
    wallet: String = WALLET,
    asOf: Instant = AS_OF,
    decimals: Int = 9,
) = ObservedBalance(
    chain = "solana",
    wallet = wallet,
    mintAddress = mint,
    amountRaw = BigInteger(amount),
    decimals = decimals,
    asOf = asOf,
    source = "wallet-api",
)

class OnchainReconTest {
    @Test
    fun `matching positions and observations are clean`() {
        val report =
            reconcileOnchain(
                positions = listOf(position(net = "500"), position(BONK, "40")),
                snapshots = listOf(observed("500"), observed("40", mint = BONK_MINT, decimals = 5)),
                instruments = instruments,
            )

        assertTrue(report.clean)
        assertEquals(2, report.deltas.size)
    }

    @Test
    fun `onchain surplus and deficit both surface as signed deltas`() {
        val report =
            reconcileOnchain(
                positions = listOf(position(net = "500")),
                snapshots = listOf(observed("700"), observed("80", mint = BONK_MINT)),
                instruments = instruments,
            )

        assertFalse(report.clean)
        val byMint = report.deltas.associateBy { it.mintAddress }
        assertEquals(BigInteger("200"), byMint.getValue(null).deltaRaw)
        assertEquals(BigInteger("80"), byMint.getValue(BONK_MINT).deltaRaw)
        assertEquals(BigInteger.ZERO, byMint.getValue(BONK_MINT).derivedRaw)
    }

    @Test
    fun `a derived position with no observation reports negative delta`() {
        val report =
            reconcileOnchain(
                positions = listOf(position(net = "500")),
                snapshots = emptyList(),
                instruments = instruments,
            )

        assertFalse(report.clean)
        val delta = report.deltas.single()
        assertEquals(BigInteger("-500"), delta.deltaRaw)
        assertEquals(BigInteger.ZERO, delta.onchainRaw)
    }

    @Test
    fun `only the latest snapshot per holding counts`() {
        val stale = observed("900", asOf = Instant.parse("2025-05-01T00:00:00Z"))
        val report =
            reconcileOnchain(
                positions = listOf(position(net = "500")),
                snapshots = listOf(stale, observed("500")),
                instruments = instruments,
            )

        assertTrue(report.clean)
        assertEquals(AS_OF, report.deltas.single().asOf)
    }

    @Test
    fun `unclassified flows make a position unverifiable not divergent`() {
        val report =
            reconcileOnchain(
                positions = listOf(position(net = "500", unclassified = "9")),
                snapshots = listOf(observed("500")),
                instruments = instruments,
            )

        assertFalse(report.clean)
        assertTrue(report.divergent.isEmpty())
        assertEquals(1, report.unverifiable.size)
    }

    @Test
    fun `a holding on an unregistered mint is reported not invented`() {
        val unknown = "So11111111111111111111111111111111111111199"
        val report =
            reconcileOnchain(
                positions = listOf(position(net = "500")),
                snapshots = listOf(observed("500"), observed("33", mint = unknown)),
                instruments = instruments,
            )

        assertFalse(report.clean)
        assertEquals(listOf(unknown), report.unregistered.map { it.mintAddress })
        assertEquals(1, report.deltas.size)
    }
}
