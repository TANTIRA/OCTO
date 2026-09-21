package com.mesta.asset.controlpanel.judgment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class CountingTransport : JudgmentTransport {
    var calls = 0

    override fun post(
        endpoint: String,
        headers: Map<String, String>,
        body: String,
    ): TransportResponse {
        calls++
        return TransportResponse(200, """{"model":"jev-1.13.0","answers":{}}""")
    }
}

class DataClassificationGuardTest {

    private val config = DecisionModelConfig(
        endpoint = DecisionModelConfig.DEFAULT_ENDPOINT,
        model = DecisionModelConfig.DEFAULT_MODEL,
        apiKey = "test-key-not-a-real-secret",
    )

    private val questions = mapOf("q" to NoulQuestion("Is it urgent?"))

    @Test
    fun `confidential state never reaches the transport`() {
        val transport = CountingTransport()

        val failure = assertFailsWith<ConfidentialStateRejectedException> {
            OpenRouterDecisionsClient(config, transport).decide(
                ClassifiedState(DataClassification.Confidential, "fund cash flow detail"),
                questions,
            )
        }

        assertEquals(DataClassification.Confidential, failure.classification)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `strictly confidential state never reaches the transport`() {
        val transport = CountingTransport()

        assertFailsWith<ConfidentialStateRejectedException> {
            OpenRouterDecisionsClient(config, transport).decide(
                ClassifiedState(DataClassification.StrictlyConfidential, "LP personal data"),
                questions,
            )
        }

        assertEquals(0, transport.calls)
    }

    @Test
    fun `the rejection message names the classification but not the payload`() {
        val transport = CountingTransport()

        val failure = assertFailsWith<ConfidentialStateRejectedException> {
            OpenRouterDecisionsClient(config, transport).decide(
                ClassifiedState(DataClassification.Confidential, "portfolio-company revenue 42.1m"),
                questions,
            )
        }

        assertFalse(failure.message!!.contains("42.1m"))
        assertTrue(failure.message!!.contains("Confidential"))
    }

    @Test
    fun `public and internal state are permitted`() {
        listOf(DataClassification.Public, DataClassification.Internal).forEach { classification ->
            val transport = CountingTransport()
            OpenRouterDecisionsClient(config, transport).decide(
                ClassifiedState(classification, "synthetic state"),
                questions,
            )
            assertEquals(1, transport.calls, "$classification should be permitted")
        }
    }

    @Test
    fun `config never prints the key`() {
        val printed = config.toString()

        assertFalse(printed.contains(config.apiKey))
        assertTrue(printed.contains("redacted"))
    }

    @Test
    fun `config reads the key from the environment and refuses to start without it`() {
        val environment = mapOf(
            "OPENROUTER_API_KEY" to "from-env",
            "DECISION_MODEL" to "typesafe/jev-1.13",
        )

        assertEquals("from-env", DecisionModelConfig.fromEnvironment(environment::get).apiKey)

        assertFailsWith<IllegalStateException> {
            DecisionModelConfig.fromEnvironment { null }
        }
    }

    @Test
    fun `blank environment values fall back to the defaults`() {
        val environment = mapOf(
            "OPENROUTER_API_KEY" to "from-env",
            "DECISION_MODEL" to "",
            "DECISION_MODEL_ENDPOINT" to "   ",
        )

        val config = DecisionModelConfig.fromEnvironment(environment::get)

        assertEquals(DecisionModelConfig.DEFAULT_MODEL, config.model)
        assertEquals(DecisionModelConfig.DEFAULT_ENDPOINT, config.endpoint)
    }

    @Test
    fun `a blank key is treated as missing`() {
        assertFailsWith<IllegalStateException> {
            DecisionModelConfig.fromEnvironment { "" }
        }
    }

    @Test
    fun `config rejects a blank key`() {
        assertFailsWith<IllegalArgumentException> {
            DecisionModelConfig(
                endpoint = DecisionModelConfig.DEFAULT_ENDPOINT,
                model = DecisionModelConfig.DEFAULT_MODEL,
                apiKey = "  ",
            )
        }
    }
}
