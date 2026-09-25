package com.mesta.asset.ingestion.onchain.helius

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val config =
    HeliusConfig(
        rpcBaseUrl = "https://devnet.helius-rpc.com",
        walletApiBaseUrl = "https://api-devnet.helius.xyz",
        apiKey = "test-key",
        network = HeliusNetwork.DEVNET,
    )

class HeliusWalletClientTest {
    private val sleeps = mutableListOf<Duration>()

    private fun client(transport: HttpTransport): HeliusWalletClient =
        HeliusWalletClient(config, transport = transport, sleeper = { sleeps += it })

    @Test
    fun `balances hits the wallet path with the api key`() {
        val transport = FakeTransport(okJson("""{"nativeBalance":5,"tokens":[]}"""))
        val result = client(transport).balances("walletX")
        val uri =
            transport.requests
                .single()
                .uri()
                .toString()
        assertTrue(uri.startsWith("https://api-devnet.helius.xyz/v1/wallet/walletX/balances"))
        assertTrue(uri.contains("api-key=test-key"))
        assertEquals(5, result["nativeBalance"].asInt())
    }

    @Test
    fun `transfers pages with limit and before`() {
        val transport = FakeTransport(okJson("""{"data":[]}"""))
        client(transport).transfers("walletX", limit = 50, before = "sig9")
        val uri =
            transport.requests
                .single()
                .uri()
                .toString()
        assertTrue(uri.contains("limit=50"))
        assertTrue(uri.contains("before=sig9"))
    }

    @Test
    fun `503 retries then succeeds`() {
        val transport = FakeTransport(statusOf(503), okJson("{}"))
        client(transport).history("walletX")
        assertEquals(2, transport.requests.size)
        assertEquals(listOf(Duration.ofSeconds(2)), sleeps)
    }

    @Test
    fun `404 is not retried`() {
        val transport = FakeTransport(statusOf(404))
        assertFailsWith<HeliusException> { client(transport).balances("walletX") }
        assertEquals(1, transport.requests.size)
    }
}
