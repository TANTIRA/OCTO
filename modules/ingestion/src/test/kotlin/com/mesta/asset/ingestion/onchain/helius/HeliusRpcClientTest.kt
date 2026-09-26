package com.mesta.asset.ingestion.onchain.helius

import com.mesta.asset.ingestion.http.FakeTransport
import com.mesta.asset.ingestion.http.HttpTransport
import com.mesta.asset.ingestion.http.okJson
import com.mesta.asset.ingestion.http.statusOf
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val config =
    HeliusConfig(
        rpcBaseUrl = "https://devnet.helius-rpc.com",
        walletApiBaseUrl = "https://api-devnet.helius.xyz",
        apiKey = "test-key",
        network = HeliusNetwork.DEVNET,
    )

class HeliusRpcClientTest {
    private val sleeps = mutableListOf<Duration>()

    private fun client(transport: HttpTransport): HeliusRpcClient =
        HeliusRpcClient(config, transport = transport, sleeper = { sleeps += it })

    @Test
    fun `config never prints the api key`() {
        assertFalse(config.toString().contains("test-key"))
    }

    @Test
    fun `signaturesForAddress pins finalized and passes the cursor`() {
        val transport =
            FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":[{"signature":"sigA","slot":10}]}"""))
        val result = client(transport).signaturesForAddress("walletX", limit = 5, before = "cursorSig")

        val body = transport.bodyOf(0)
        assertEquals("getSignaturesForAddress", body["method"].asText())
        assertEquals("finalized", body["params"][1]["commitment"].asText())
        assertEquals("cursorSig", body["params"][1]["before"].asText())
        assertTrue(
            transport.requests[0]
                .uri()
                .toString()
                .contains("api-key=test-key"),
        )
        assertEquals("sigA", result[0]["signature"].asText())
    }

    @Test
    fun `transaction returns null on a null result`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":null}"""))
        assertNull(client(transport).transaction("sigGone"))
        val body = transport.bodyOf(0)
        assertEquals("jsonParsed", body["params"][1]["encoding"].asText())
        assertEquals("finalized", body["params"][1]["commitment"].asText())
    }

    @Test
    fun `balance reads lamports from result value`() {
        val transport =
            FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":7},"value":123456789}}"""))
        assertEquals(123456789L, client(transport).balance("walletX"))
    }

    @Test
    fun `tokenAccountsByOwner queries the SPL token program parsed`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":{"value":[]}}"""))
        client(transport).tokenAccountsByOwner("walletX")
        val body = transport.bodyOf(0)
        assertEquals(HeliusRpcClient.SPL_TOKEN_PROGRAM_ID, body["params"][1]["programId"].asText())
        assertEquals("jsonParsed", body["params"][2]["encoding"].asText())
    }

    @Test
    fun `429 is retried honoring Retry-After`() {
        val transport =
            FakeTransport(
                statusOf(429, retryAfterSeconds = 3),
                okJson("""{"jsonrpc":"2.0","id":1,"result":{"context":{},"value":1}}"""),
            )
        client(transport).balance("walletX")
        assertEquals(2, transport.requests.size)
        assertEquals(Duration.ofSeconds(3), sleeps.single())
    }

    @Test
    fun `a 400 fails immediately without retrying`() {
        val transport = FakeTransport(statusOf(400))
        assertFailsWith<HeliusException> { client(transport).balance("walletX") }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `rpc error objects raise HeliusException without retry`() {
        val transport =
            FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad params"}}"""))
        val e = assertFailsWith<HeliusException> { client(transport).balance("walletX") }
        assertTrue(e.message!!.contains("bad params"))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `persistent 500 exhausts attempts then throws`() {
        val transport = FakeTransport(statusOf(500), statusOf(500), statusOf(500))
        val e = assertFailsWith<HeliusException> { client(transport).balance("walletX") }
        assertEquals(500, e.status)
        assertEquals(3, transport.requests.size)
        assertEquals(listOf(Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps)
    }

    @Test
    fun `stakeAccounts filters both authority offsets and merges deduped`() {
        val acct = """{"pubkey":"stakeAcct1","account":{"data":{"parsed":{"info":{}}}}}"""
        val transport =
            FakeTransport(
                okJson("""{"jsonrpc":"2.0","id":1,"result":[$acct]}"""),
                okJson("""{"jsonrpc":"2.0","id":2,"result":[$acct]}"""),
            )

        val result = client(transport).stakeAccounts("walletX")

        assertEquals(2, transport.requests.size)
        for (i in 0..1) {
            val body = transport.bodyOf(i)
            assertEquals("getProgramAccounts", body["method"].asText())
            assertEquals(HeliusRpcClient.STAKE_PROGRAM_ID, body["params"][0].asText())
            val memcmp = body["params"][1]["filters"][0]["memcmp"]
            assertEquals(listOf(44, 76)[i], memcmp["offset"].asInt())
            assertEquals("walletX", memcmp["bytes"].asText())
            assertEquals("base58", memcmp["encoding"].asText())
            assertEquals("finalized", body["params"][1]["commitment"].asText())
        }
        // Same pubkey under both authorities merges into one account.
        assertEquals(1, result.size())
        assertEquals("stakeAcct1", result[0]["pubkey"].asText())
    }

    @Test
    fun `inflationReward passes addresses in order and the epoch`() {
        val transport =
            FakeTransport(
                okJson("""{"jsonrpc":"2.0","id":1,"result":[{"epoch":700,"amount":10}]}"""),
            )

        client(transport).inflationReward(listOf("acctA", "acctB"), epoch = 700)

        val params = transport.bodyOf(0)["params"]
        assertEquals("acctA", params[0].asText())
        assertEquals("acctB", params[1].asText())
        assertEquals(700, params[2]["epoch"].asLong())
        assertEquals("finalized", params[2]["commitment"].asText())
    }

    @Test
    fun `blockTime returns null when the RPC errors`() {
        val transport =
            FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"error":{"code":-32009,"message":"Slot 1 was skipped"}}"""))

        assertNull(client(transport).blockTime(1))
    }
}
