package com.octo.ingestion.onchain

import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A balance leg staged before the fee was split out keeps `|post - pre|`, fee included, under
 * `bal:0`. Re-normalization emits a smaller `bal:0` (deduped away) plus a new `fee` leg.
 * [omitSolanaFeesAlreadyBooked] must drop that fee so the lamports are not debited twice.
 */
class SolanaFeeBookingTest {
    @Test
    fun `a legacy outgoing balance already includes the fee, so the new fee leg is dropped`() {
        val kept =
            omitSolanaFeesAlreadyBooked(
                listOf(native(OUT, 1_000_000_000), fee(5_000), tokenOut()),
                listOf(staged(OUT, 1_000_005_000)),
            )
        assertEquals(listOf(balId, tokenId), kept.map { it.externalId })
    }

    @Test
    fun `a legacy incoming balance already nets the fee, so the new fee leg is dropped`() {
        val kept =
            omitSolanaFeesAlreadyBooked(
                listOf(native(IN, 1_000_000_000), fee(5_000)),
                listOf(staged(IN, 999_995_000)),
            )
        assertEquals(listOf(balId), kept.map { it.externalId })
    }

    @Test
    fun `a legacy native debit of exactly the fee is not paired with a new fee leg`() {
        val kept =
            omitSolanaFeesAlreadyBooked(
                listOf(fee(5_000)),
                listOf(staged(OUT, 5_000)),
            )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `a receive smaller than the fee still matches the legacy net outflow`() {
        val kept =
            omitSolanaFeesAlreadyBooked(
                listOf(native(IN, 1_000), fee(5_000)),
                listOf(staged(OUT, 4_000)),
            )
        assertEquals(listOf(balId), kept.map { it.externalId })
    }

    @Test
    fun `a fee-free balance still accepts its fee leg`() {
        val incoming = listOf(native(OUT, 1_000_000_000), fee(5_000))
        val kept =
            omitSolanaFeesAlreadyBooked(
                incoming,
                listOf(staged(OUT, 1_000_000_000)),
            )
        assertEquals(incoming.map { it.externalId }, kept.map { it.externalId })
    }

    @Test
    fun `nothing staged keeps the fee-split legs`() {
        val incoming = listOf(native(OUT, 1_000_000_000), fee(5_000))
        assertEquals(incoming, omitSolanaFeesAlreadyBooked(incoming, emptyList()))
    }

    @Test
    fun `a staged amount that is not the legacy inclusive leg does not hide the fee`() {
        val incoming = listOf(native(OUT, 1_000_000_000), fee(5_000))
        val kept = omitSolanaFeesAlreadyBooked(incoming, listOf(staged(OUT, 42)))
        assertEquals(incoming.map { it.externalId }, kept.map { it.externalId })
    }

    @Test
    fun `the same external id under another source system is a different fact`() {
        val incoming = listOf(native(OUT, 1_000_000_000), fee(5_000))
        val kept =
            omitSolanaFeesAlreadyBooked(
                incoming,
                listOf(staged(OUT, 1_000_005_000).copy(sourceSystem = "other-system")),
            )
        assertEquals(incoming.map { it.externalId }, kept.map { it.externalId })
    }

    @Test
    fun `only the signature whose legacy leg is staged loses its fee`() {
        val otherBal = "solana:$OTHER:$WALLET:bal:0"
        val otherFee = "solana:$OTHER:$WALLET:fee"
        val incoming =
            listOf(
                native(OUT, 1_000_000_000),
                fee(5_000),
                native(OUT, 2_000, signature = OTHER).copy(externalId = otherBal),
                fee(5_000, signature = OTHER).copy(externalId = otherFee),
            )
        val kept = omitSolanaFeesAlreadyBooked(incoming, listOf(staged(OUT, 1_000_005_000)))
        assertEquals(listOf(balId, otherBal, otherFee), kept.map { it.externalId })
    }

    @Test
    fun `the legacy balance id for a fee leg is bal colon 0`() {
        assertEquals("solana:sig:acct:bal:0", legacyBalanceExternalId("solana:sig:acct:fee"))
        assertEquals(null, legacyBalanceExternalId("solana:sig:acct:bal:0"))
    }

    private fun native(
        direction: TransferDirection,
        amount: Long,
        signature: String = SIG,
    ) = leg(
        externalId = "solana:$signature:$WALLET:bal:0",
        direction = direction,
        amount = amount,
        signature = signature,
    )

    private fun fee(
        amount: Long,
        signature: String = SIG,
    ) = leg(
        externalId = "solana:$signature:$WALLET:fee",
        direction = TransferDirection.FEE,
        amount = amount,
        signature = signature,
    )

    private fun tokenOut() =
        leg(
            externalId = tokenId,
            direction = TransferDirection.OUT,
            amount = 300,
        ).copy(
            tokenAccount = "tAcct",
            mintAddress = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            decimals = 6,
        )

    private fun staged(
        direction: TransferDirection,
        amount: Long,
    ) = StagedNativeLeg(
        sourceSystem = ONCHAIN_SOURCE_SYSTEM,
        externalId = balId,
        amountRaw = BigInteger.valueOf(amount),
        direction = direction,
    )

    private fun leg(
        externalId: String,
        direction: TransferDirection,
        amount: Long,
        signature: String = SIG,
    ) = OnchainTransfer(
        externalId = externalId,
        signature = signature,
        slot = 250_000_000,
        blockHash = "bh",
        blockTime = Instant.parse("2026-09-27T00:00:00Z"),
        wallet = WALLET,
        counterparty = null,
        tokenAccount = null,
        mintAddress = null,
        amountRaw = BigInteger.valueOf(amount),
        decimals = 9,
        direction = direction,
        transferKind = if (direction == TransferDirection.IN) TransferKind.TRANSFER_IN else TransferKind.TRANSFER_OUT,
    )

    private companion object {
        const val WALLET = "7VVAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SIG = "sig-legacy"
        const val OTHER = "sig-fresh"
        val IN = TransferDirection.IN
        val OUT = TransferDirection.OUT
        val balId = "solana:$SIG:$WALLET:bal:0"
        val tokenId = "solana:$SIG:tAcct:tok:1"
    }
}
