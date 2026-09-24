package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.onchain.TransferDirection
import com.mesta.asset.ingestion.onchain.TransferKind
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val WALLET = "7VVA" + "A".repeat(39)
private val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" // devnet-style USDC mint
private val SIG = "5wHuPkQ" + "s".repeat(80)

private fun tx(
    preLamports: List<Long>,
    postLamports: List<Long>,
    accounts: List<String>,
    preTokens: String = "[]",
    postTokens: String = "[]",
    err: String = "null",
    blockTime: Long = 1_726_000_000,
    slot: Long = 250_000_000,
): String =
    """
    {
      "slot": $slot,
      "blockTime": $blockTime,
      "transaction": {
        "signatures": ["$SIG"],
        "message": {
          "recentBlockhash": "bh123",
          "accountKeys": [${accounts.joinToString(",") { """{"pubkey":"$it","signer":false}""" }}]
        }
      },
      "meta": {
        "err": $err,
        "preBalances": [${preLamports.joinToString(",")}],
        "postBalances": [${postLamports.joinToString(",")}],
        "preTokenBalances": $preTokens,
        "postTokenBalances": $postTokens
      }
    }
    """.trimIndent()

private fun tokenEntry(
    accountIndex: Int,
    mint: String,
    owner: String,
    amount: String,
    decimals: Int,
) = """{"accountIndex":$accountIndex,"mint":"$mint","owner":"$owner",
       "uiTokenAmount":{"amount":"$amount","decimals":$decimals}}"""

class HeliusTransferNormalizerTest {
    private val mapper = ObjectMapper()
    private val normalizer = HeliusTransferNormalizer()

    private fun normalize(
        json: String,
        wallet: String = WALLET,
    ) = normalizer.normalize(mapper.readTree(json), wallet)

    @Test
    fun `incoming native SOL produces one IN leg in lamports`() {
        val legs = normalize(tx(preLamports = listOf(1_000), postLamports = listOf(1_500_000_000), accounts = listOf(WALLET)))
        val leg = legs.single()
        assertEquals(TransferDirection.IN, leg.direction)
        assertEquals(TransferKind.TRANSFER_IN, leg.transferKind)
        assertEquals(BigInteger("1499999000"), leg.amountRaw)
        assertEquals(9, leg.decimals)
        assertEquals("solana:$SIG:$WALLET:bal:0", leg.externalId)
        assertEquals(250_000_000L, leg.slot)
        assertEquals("bh123", leg.blockHash)
    }

    @Test
    fun `outgoing SOL produces an OUT leg`() {
        val legs = normalize(tx(preLamports = listOf(2_000_000_000), postLamports = listOf(999_995_000), accounts = listOf(WALLET)))
        val leg = legs.single()
        assertEquals(TransferDirection.OUT, leg.direction)
        assertEquals(BigInteger("1000005000"), leg.amountRaw)
    }

    @Test
    fun `a failed transaction produces no facts`() {
        val json =
            tx(
                preLamports = listOf(2_000),
                postLamports = listOf(1_000),
                accounts = listOf(WALLET),
                err = """{"InstructionError":[0,"Custom"]}""",
            )
        assertTrue(normalize(json).isEmpty())
    }

    @Test
    fun `an SPL token credit produces an IN leg with mint and token account`() {
        val accounts = listOf("payerX", "AtokenAcct111" + "1".repeat(30))
        val post = """[${tokenEntry(1, MINT, WALLET, "250000000", 6)}]"""
        val legs = normalize(tx(preLamports = listOf(5_000, 0), postLamports = listOf(5_000, 0), accounts = accounts, postTokens = post))
        val leg = legs.single()
        assertEquals(TransferKind.TRANSFER_IN, leg.transferKind)
        assertEquals(MINT, leg.mintAddress)
        assertEquals(accounts[1], leg.tokenAccount)
        assertEquals(BigInteger("250000000"), leg.amountRaw)
        assertEquals(6, leg.decimals)
        assertEquals("solana:$SIG:${accounts[1]}:tok:1", leg.externalId)
    }

    @Test
    fun `an SPL token debit produces an OUT leg`() {
        val accounts = listOf("wAcct", "tAcct")
        val pre = """[${tokenEntry(1, MINT, WALLET, "400", 6)}]"""
        val post = """[${tokenEntry(1, MINT, WALLET, "100", 6)}]"""
        val legs =
            normalize(tx(preLamports = listOf(0, 0), postLamports = listOf(0, 0), accounts = accounts, preTokens = pre, postTokens = post))
        assertEquals(TransferDirection.OUT, legs.single().direction)
        assertEquals(BigInteger("300"), legs.single().amountRaw)
    }

    @Test
    fun `the same mint held in two token accounts stays two distinct legs`() {
        val accounts = listOf("w", "acct1", "acct2")
        val pre = """[${tokenEntry(1, MINT, WALLET, "100", 6)}, ${tokenEntry(2, MINT, WALLET, "100", 6)}]"""
        val post = """[${tokenEntry(1, MINT, WALLET, "50", 6)}, ${tokenEntry(2, MINT, WALLET, "150", 6)}]"""
        val legs =
            normalize(
                tx(preLamports = listOf(0, 0, 0), postLamports = listOf(0, 0, 0), accounts = accounts, preTokens = pre, postTokens = post),
            )
        assertEquals(2, legs.size)
        assertEquals(setOf("acct1", "acct2"), legs.map { it.tokenAccount }.toSet())
        assertEquals(setOf(TransferDirection.OUT, TransferDirection.IN), legs.map { it.direction }.toSet())
    }

    @Test
    fun `normalization is deterministic so poller and webhook dedupe`() {
        val json = tx(preLamports = listOf(1_000), postLamports = listOf(9_000), accounts = listOf(WALLET))
        val first = normalize(json)
        val second = normalize(json)
        assertEquals(first.map { it.externalId }, second.map { it.externalId })
    }

    @Test
    fun `a transaction that does not touch the wallet produces nothing`() {
        val legs = normalize(tx(preLamports = listOf(1_000, 2_000), postLamports = listOf(500, 2_500), accounts = listOf("alice", "bob")))
        assertTrue(legs.isEmpty())
    }
}
