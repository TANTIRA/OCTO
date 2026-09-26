package com.mesta.asset.ingestion.onchain.helius

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HeliusConfigTest {
    private val config =
        HeliusConfig(
            rpcBaseUrl = "https://devnet.helius-rpc.com",
            walletApiBaseUrl = "https://api-devnet.helius.xyz",
            apiKey = "test-key",
            network = HeliusNetwork.DEVNET,
        )

    @Test
    fun `toString never prints the api key`() {
        assertFalse(config.toString().contains("test-key"))
    }

    @Test
    fun `non-https endpoints and blank keys are rejected`() {
        assertFailsWith<IllegalArgumentException> { config.copy(rpcBaseUrl = "http://insecure") }
        assertFailsWith<IllegalArgumentException> { config.copy(apiKey = "  ") }
    }

    @Test
    fun `network carries mainnet or devnet`() {
        assertEquals(HeliusNetwork.DEVNET, config.network)
    }
}
