package com.mesta.asset.controlpanel.judgment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DecisionModelConfigTest {
    @Test
    fun `the default endpoint is the documented decisions URL, not the doubled-api one that 404s`() {
        // #70: the default was https://openrouter.ai/api/v1/api/alpha/decisions, which OpenRouter answers with 404.
        assertEquals("https://openrouter.ai/api/alpha/decisions", DecisionModelConfig.DEFAULT_ENDPOINT)
        assertFalse(DecisionModelConfig.DEFAULT_ENDPOINT.contains("/api/v1/api/"))
    }

    @Test
    fun `fromEnvironment falls back to the default endpoint and model`() {
        val config = DecisionModelConfig.fromEnvironment { if (it == "OPENROUTER_API_KEY") "test-key" else null }
        assertEquals(DecisionModelConfig.DEFAULT_ENDPOINT, config.endpoint)
        assertEquals(DecisionModelConfig.DEFAULT_MODEL, config.model)
    }
}
