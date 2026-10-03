package com.octo.ingestion.onchain.evm

import com.octo.ingestion.http.FakeTransport
import com.octo.ingestion.http.HttpTransport
import com.octo.ingestion.http.okJson
import com.octo.ingestion.http.statusOf
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val config =
    EvmConfig(
        rpcBaseUrl = "https://arb-sepolia.example.com/rpc/test-key",
        chain = "arbitrum-sepolia",
        chainId = 421614,
        apiKey = "test-key",
    )

class EvmRpcClientTest {
    private val sleeps = mutableListOf<Duration>()

    private fun client(transport: HttpTransport): EvmRpcClient = EvmRpcClient(config, transport = transport, sleeper = { sleeps += it })

    @Test
    fun `config never prints the endpoint or the api key`() {
        // The URL commonly embeds the credential in its path, so both stay out of logs.
        assertFalse(config.toString().contains("arb-sepolia.example.com"))
        assertFalse(config.toString().contains("test-key"))
    }

    @Test
    fun `chainId parses the hex quantity`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"0xa4b1"}"""))
        assertEquals(42161L, client(transport).chainId())
        assertEquals("eth_chainId", transport.bodyOf(0)["method"].asText())
    }

    @Test
    fun `finalizedBlock pins the finalized tag`() {
        val transport =
            FakeTransport(
                okJson("""{"jsonrpc":"2.0","id":1,"result":{"number":"0x64","hash":"0xabc","timestamp":"0x66000000"}}"""),
            )
        val block = client(transport).finalizedBlock()

        val body = transport.bodyOf(0)
        assertEquals("eth_getBlockByNumber", body["method"].asText())
        assertEquals("finalized", body["params"][0].asText())
        assertEquals("0x64", block["number"].asText())
    }

    @Test
    fun `transferLogs filters the padded addresses on the requested side`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":[]}"""))
        client(transport).transferLogs(100, 199, listOf("0xAaAa00000000000000000000000000000000aaaa"), fromSide = true)

        val filter = transport.bodyOf(0)["params"][0]
        assertEquals("eth_getLogs", transport.bodyOf(0)["method"].asText())
        assertEquals("0x64", filter["fromBlock"].asText())
        assertEquals("0xc7", filter["toBlock"].asText())
        val topics = filter["topics"]
        assertEquals(EvmRpcClient.TRANSFER_TOPIC, topics[0].asText())
        assertEquals("0x000000000000000000000000aaaa00000000000000000000000000000000aaaa", topics[1][0].asText())
        assertTrue(topics[2].isNull)
    }

    @Test
    fun `transferLogs with fromSide false filters recipients`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":[]}"""))
        client(transport).transferLogs(1, 2, listOf("0xbbbb00000000000000000000000000000000bbbb"), fromSide = false)

        val topics = transport.bodyOf(0)["params"][0]["topics"]
        assertTrue(topics[1].isNull)
        assertEquals("0x000000000000000000000000bbbb00000000000000000000000000000000bbbb", topics[2][0].asText())
    }

    @Test
    fun `nativeBalance reads wei at finalized`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"0xde0b6b3a7640000"}"""))
        assertEquals(BigInteger("1000000000000000000"), client(transport).nativeBalance("0xw"))

        val params = transport.bodyOf(0)["params"]
        assertEquals("eth_getBalance", transport.bodyOf(0)["method"].asText())
        assertEquals("finalized", params[1].asText())
    }

    @Test
    fun `balanceOf encodes the selector and padded address`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}"""))
        assertEquals(
            BigInteger("42"),
            client(transport).balanceOf("0xcontract", "0x1111111111111111111111111111111111111111"),
        )

        val call = transport.bodyOf(0)["params"][0]
        assertEquals("eth_call", transport.bodyOf(0)["method"].asText())
        assertEquals("0xcontract", call["to"].asText())
        assertEquals("0x70a082310000000000000000000000001111111111111111111111111111111111111111", call["data"].asText())
        assertEquals("finalized", transport.bodyOf(0)["params"][1].asText())
    }

    @Test
    fun `a reverting eth_call returns null rather than throwing`() {
        val revert = okJson("""{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted"}}""")
        val transport = FakeTransport(revert, revert)
        assertNull(client(transport).decimals("0xnotatoken"))
        assertNull(client(transport).balanceOf("0xnotatoken", "0xw"))
        // An empty return payload means the method does not exist.
        val empty = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"0x"}"""))
        assertNull(client(empty).decimals("0xeoa"))
        // geth's other revert shape: -32000 with the revert in the message.
        val gethRevert = okJson("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"execution reverted"}}""")
        assertNull(client(FakeTransport(gethRevert)).decimals("0xnotatoken"))
    }

    @Test
    fun `a non-revert JSON-RPC error on eth_call propagates instead of reading as not-a-token`() {
        for (error in listOf(
            """{"code":-32005,"message":"rate limit exceeded"}""",
            """{"code":-32000,"message":"missing trie node"}""",
            """{"code":-32000,"message":"header not found"}""",
        )) {
            val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"error":$error}"""))
            assertFailsWith<EvmException> { client(transport).decimals("0xusdc") }
        }
    }

    @Test
    fun `a deterministic VM failure reads as an unreadable contract, not a provider outage`() {
        for (message in listOf("invalid opcode: INVALID", "out of gas", "invalid jump destination")) {
            val transport =
                FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"$message"}}"""))
            assertNull(client(transport).decimals("0xhostile"))
        }
    }

    @Test
    fun `decimals reads the uint8 and rejects nonsense`() {
        val six = "0x" + "0".repeat(62) + "06"
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"$six"}"""))
        assertEquals(6, client(transport).decimals("0xusdc"))

        val bogus = "0x" + "f".repeat(64)
        val broken = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"$bogus"}"""))
        assertNull(client(broken).decimals("0xbroken"))
    }

    @Test
    fun `a 429 is retried honoring Retry-After`() {
        val transport =
            FakeTransport(
                statusOf(429, retryAfterSeconds = 3),
                okJson("""{"jsonrpc":"2.0","id":1,"result":"0xa4b1"}"""),
            )
        client(transport).chainId()
        assertEquals(2, transport.requests.size)
        assertEquals(Duration.ofSeconds(3), sleeps.single())
    }

    @Test
    fun `a 400 fails immediately without retrying`() {
        val transport = FakeTransport(statusOf(400))
        assertFailsWith<EvmException> { client(transport).chainId() }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `rpc error objects raise EvmException without retry`() {
        val transport =
            FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"header not found"}}"""))
        val e = assertFailsWith<EvmException> { client(transport).chainId() }
        assertTrue(e.message!!.contains("header not found"))
        assertNull(e.status)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `persistent 500 exhausts attempts then throws`() {
        val transport = FakeTransport(statusOf(500), statusOf(500), statusOf(500))
        val e = assertFailsWith<EvmException> { client(transport).chainId() }
        assertEquals(500, e.status)
        assertEquals(3, transport.requests.size)
        assertEquals(listOf(Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps)
    }

    @Test
    fun `a malformed balance fails closed as an rpc error, never a fabricated zero`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"0xzz"}"""))
        val e = assertFailsWith<EvmException> { client(transport).nativeBalance("0xabc") }
        assertNull(e.status)
    }

    @Test
    fun `malformed eth_call returns read as absent rather than coerced`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"0xzz"}"""))
        assertNull(client(transport).decimals("0xcontract"))
    }

    @Test
    fun `the api key rides as a bearer token`() {
        val transport = FakeTransport(okJson("""{"jsonrpc":"2.0","id":1,"result":"0xa4b1"}"""))
        client(transport).chainId()
        assertEquals(
            "Bearer test-key",
            transport.requests[0]
                .headers()
                .firstValue("Authorization")
                .orElse(null),
        )
    }
}
