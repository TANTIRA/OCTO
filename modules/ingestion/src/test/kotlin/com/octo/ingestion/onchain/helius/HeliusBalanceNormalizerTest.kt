package com.octo.ingestion.onchain.helius

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.ingestion.onchain.BalanceSource
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
private const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
private val AS_OF = Instant.parse("2025-06-01T00:00:00Z")

private fun tree(json: String) = ObjectMapper().readTree(json)

class HeliusBalanceNormalizerTest {
    private val normalizer = HeliusBalanceNormalizer()

    @Test
    fun `wallet api balances normalize including wrapped-sol native row`() {
        val json =
            tree(
                """
                {"balances":[
                   {"mint":"${HeliusBalanceNormalizer.WRAPPED_SOL_MINT}","symbol":"SOL","balance":1.5,"decimals":9,"usdValue":217.5,"tokenProgram":"spl-token"},
                   {"mint":"$USDC","symbol":"USDC","balance":284961463.392936,"decimals":6,"usdValue":284961463.39,"tokenProgram":"spl-token"}],
                 "totalUsdValue":285.0,"pagination":{"page":1,"limit":100,"hasMore":false}}
                """.trimIndent(),
            )

        val parsed = normalizer.fromWalletApi(json, WALLET, AS_OF)

        assertEquals(2, parsed.balances.size)
        assertNull(parsed.balances[0].mintAddress)
        assertEquals(BigInteger("1500000000"), parsed.balances[0].amountRaw)
        assertEquals(USDC, parsed.balances[1].mintAddress)
        assertEquals(BigInteger("284961463392936"), parsed.balances[1].amountRaw)
        assertEquals(6, parsed.balances[1].decimals)
        assertEquals(BalanceSource.WALLET_API, parsed.balances[1].source)
        assertEquals(AS_OF, parsed.balances[1].asOf)
    }

    @Test
    fun `a non-integral raw amount is skipped rather than truncated`() {
        val json =
            tree(
                """
                {"balances":[
                   {"mint":"$USDC","balance":1.0000000000001,"decimals":6,"tokenProgram":"spl-token"},
                   {"mint":"$USDC","balance":2.5,"decimals":6,"tokenProgram":"spl-token"}],
                 "pagination":{"page":1,"limit":100,"hasMore":false}}
                """.trimIndent(),
            )

        val parsed = normalizer.fromWalletApi(json, WALLET, AS_OF)

        assertEquals(1, parsed.balances.size)
        assertEquals(listOf(USDC), parsed.skipped)
        assertEquals(BigInteger("2500000"), parsed.balances[0].amountRaw)
    }

    @Test
    fun `a missing, null or non-numeric balance or decimals is skipped, not stored as zero`() {
        val json =
            tree(
                """
                {"balances":[
                   {"mint":"m-missing","decimals":6},
                   {"mint":"m-null","balance":null,"decimals":6},
                   {"mint":"m-text","balance":"12.5","decimals":6},
                   {"mint":"m-no-decimals","balance":12.5},
                   {"mint":"m-text-decimals","balance":12.5,"decimals":"6"},
                   {"mint":"$USDC","balance":0,"decimals":6}],
                 "pagination":{"page":1,"limit":100,"hasMore":false}}
                """.trimIndent(),
            )

        val parsed = normalizer.fromWalletApi(json, WALLET, AS_OF)

        assertEquals(listOf("m-missing", "m-null", "m-text", "m-no-decimals", "m-text-decimals"), parsed.skipped)
        assertEquals(USDC, parsed.balances.single().mintAddress)
        assertEquals(BigInteger.ZERO, parsed.balances.single().amountRaw)
    }

    @Test
    fun `hasMore drives paging`() {
        val more = tree("""{"balances":[],"pagination":{"page":1,"limit":100,"hasMore":true}}""")

        assertTrue(normalizer.fromWalletApi(more, WALLET, AS_OF).hasMore)
    }

    @Test
    fun `rpc fallback yields native lamports and per-mint sums across token accounts`() {
        val accounts =
            tree(
                """
                {"value":[
                   {"account":{"data":{"parsed":{"info":{"mint":"$USDC","tokenAmount":{"amount":"100","decimals":6}}}}}},
                   {"account":{"data":{"parsed":{"info":{"mint":"$USDC","tokenAmount":{"amount":"250","decimals":6}}}}}},
                   {"account":{"data":{"parsed":{"info":{"mint":"$USDC","tokenAmount":{"amount":"0","decimals":6}}}}}}]}
                """.trimIndent(),
            )

        val parsed = normalizer.fromRpc(1_500_000_000, accounts, WALLET, AS_OF)

        assertEquals(2, parsed.balances.size)
        assertNull(parsed.balances[0].mintAddress)
        assertEquals(BigInteger("1500000000"), parsed.balances[0].amountRaw)
        assertEquals(BigInteger("350"), parsed.balances[1].amountRaw)
        assertEquals(BalanceSource.RPC, parsed.balances[1].source)
        assertEquals(emptyList(), parsed.skipped)
        assertEquals(false, parsed.unidentified)
    }

    @Test
    fun `an unreadable rpc token account is reported and a partial mint sum is dropped`() {
        val accounts =
            tree(
                """
                {"value":[
                   {"pubkey":"unparsed","account":{"data":["AAAA","base64"]}},
                   {"account":{"data":{"parsed":{"info":{"mint":"$USDC","tokenAmount":{"amount":"100","decimals":6}}}}}},
                   {"account":{"data":{"parsed":{"info":{"mint":"$USDC","tokenAmount":{"amount":null,"decimals":6}}}}}},
                   {"account":{"data":{"parsed":{"info":{"mint":"NegMint111","tokenAmount":{"amount":"-1","decimals":6}}}}}},
                   {"account":{"data":{"parsed":{"info":{"mint":"GoodMint111","tokenAmount":{"amount":"5","decimals":6}}}}}}]}
                """.trimIndent(),
            )

        val parsed = normalizer.fromRpc(0, accounts, WALLET, AS_OF)

        assertEquals(true, parsed.unidentified)
        assertEquals(listOf(USDC, "NegMint111"), parsed.skipped)
        assertEquals("GoodMint111", parsed.balances.single().mintAddress)
        assertEquals(BigInteger("5"), parsed.balances.single().amountRaw)
    }
}
