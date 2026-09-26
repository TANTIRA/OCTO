package com.mesta.asset.ingestion.marketdata.alphavantage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AlphaVantageConfigTest {
    @Test
    fun `config requires https and a key, and never prints the key`() {
        assertFailsWith<IllegalArgumentException> { AlphaVantageConfig(baseUrl = "http://x", apiKey = "k") }
        assertFailsWith<IllegalArgumentException> { AlphaVantageConfig(apiKey = "  ") }
        assertFalse(AlphaVantageConfig(apiKey = "test-key").toString().contains("test-key"))
    }

    @Test
    fun `fromEnvironment reads the key and defaults the base url`() {
        val config = AlphaVantageConfig.fromEnvironment { if (it == "ALPHA_VANTAGE_API_KEY") "env-key" else null }
        assertEquals("env-key", config.apiKey)
        assertEquals(AlphaVantageConfig.DEFAULT_BASE_URL, config.baseUrl)
    }

    @Test
    fun `fromEnvironment honors an override url and fails without a key`() {
        val env = mapOf("ALPHA_VANTAGE_API_KEY" to "k", "ALPHA_VANTAGE_BASE_URL" to "https://mirror.example")
        assertEquals("https://mirror.example", AlphaVantageConfig.fromEnvironment(env::get).baseUrl)
        assertFailsWith<IllegalStateException> { AlphaVantageConfig.fromEnvironment { null } }
    }
}
