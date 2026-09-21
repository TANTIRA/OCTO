package com.mesta.asset.controlpanel.judgment

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordingTransport(private val response: TransportResponse) : JudgmentTransport {
    var calls = 0
    var lastEndpoint: String? = null
    var lastHeaders: Map<String, String> = emptyMap()
    var lastBody: String? = null

    override fun post(
        endpoint: String,
        headers: Map<String, String>,
        body: String,
    ): TransportResponse {
        calls++
        lastEndpoint = endpoint
        lastHeaders = headers
        lastBody = body
        return response
    }
}

class OpenRouterDecisionsClientTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private val config = DecisionModelConfig(
        endpoint = DecisionModelConfig.DEFAULT_ENDPOINT,
        model = DecisionModelConfig.DEFAULT_MODEL,
        apiKey = "test-key-not-a-real-secret",
    )

    private fun client(transport: JudgmentTransport) =
        OpenRouterDecisionsClient(config, transport)

    private fun ok(body: String) = RecordingTransport(TransportResponse(200, body))

    @Test
    fun `sends typed questions in the documented request shape`() {
        val transport = ok("""{"model":"jev-1.13.0","answers":{}}""")

        client(transport).decide(
            ClassifiedState(
                DataClassification.Internal,
                mapOf("ticket" to "Checkout shows a blank screen"),
            ),
            mapOf(
                "is_bug" to NoulQuestion(
                    "Is the customer reporting a software defect?",
                    NoulCriteria(
                        whenTrue = "Describes broken behaviour",
                        whenFalse = "Asks a question or requests a feature",
                    ),
                ),
                "team" to ChoiceQuestion(
                    "Which team should own this ticket?",
                    mapOf("billing" to "Payments", "technical" to "Bugs"),
                ),
                "urgency" to ScoreQuestion(
                    "How urgent is this ticket?",
                    listOf("Can wait", "This week", "Blocking revenue"),
                ),
            ),
        )

        val body = mapper.readTree(transport.lastBody!!)

        assertEquals(DecisionModelConfig.DEFAULT_MODEL, body["model"].asText())
        assertEquals("Checkout shows a blank screen", body["state"]["ticket"].asText())

        assertEquals("noul", body["questions"]["is_bug"]["type"].asText())
        assertEquals(
            "Is the customer reporting a software defect?",
            body["questions"]["is_bug"]["instructions"].asText(),
        )
        assertEquals("Describes broken behaviour", body["questions"]["is_bug"]["criteria"]["true"].asText())
        assertEquals(
            "Asks a question or requests a feature",
            body["questions"]["is_bug"]["criteria"]["false"].asText(),
        )

        assertEquals("choice", body["questions"]["team"]["type"].asText())
        assertEquals("Payments", body["questions"]["team"]["criteria"]["billing"].asText())
        assertEquals("Bugs", body["questions"]["team"]["criteria"]["technical"].asText())

        assertEquals("score", body["questions"]["urgency"]["type"].asText())
        assertEquals(3, body["questions"]["urgency"]["criteria"].size())
        assertEquals("Blocking revenue", body["questions"]["urgency"]["criteria"][2].asText())
    }

    @Test
    fun `does not allow provider fallback by default`() {
        val transport = ok("""{"model":"jev-1.13.0","answers":{}}""")

        client(transport).decide(
            ClassifiedState(DataClassification.Internal, "state"),
            mapOf("q" to NoulQuestion("Is it urgent?")),
        )

        val body = mapper.readTree(transport.lastBody!!)
        assertFalse(body["provider"]["allow_fallbacks"].asBoolean())
    }

    @Test
    fun `sends the key as a bearer header and never in the body`() {
        val transport = ok("""{"model":"jev-1.13.0","answers":{}}""")

        client(transport).decide(
            ClassifiedState(DataClassification.Internal, "state"),
            mapOf("q" to NoulQuestion("Is it urgent?")),
        )

        assertEquals("Bearer ${config.apiKey}", transport.lastHeaders["Authorization"])
        assertFalse(transport.lastBody!!.contains(config.apiKey))
        assertEquals(DecisionModelConfig.DEFAULT_ENDPOINT, transport.lastEndpoint)
    }

    @Test
    fun `reads back noul, choice and score answers`() {
        val transport = ok(
            """
            {
              "model": "jev-1.13.0",
              "id": "gen-abc123",
              "provider": "TypeSafe",
              "answers": {
                "is_urgent": { "type": "noul", "noul": 0.95 },
                "department": {
                  "type": "choice",
                  "choice": "billing",
                  "probabilities": { "billing": 0.88, "technical": 0.12 },
                  "confidence": 0.81
                },
                "frustration": {
                  "type": "score",
                  "score": 1.05,
                  "legend": { "0": "Calm", "1": "Frustrated", "2": "Very angry" },
                  "probabilities": { "0": 0.0, "1": 0.95, "2": 0.05 },
                  "confidence": 0.92
                }
              },
              "usage": { "input_tokens": 318, "output_tokens": 34, "cost": 0.0000134 }
            }
            """.trimIndent(),
        )

        val result = client(transport).decide(
            ClassifiedState(DataClassification.Internal, "state"),
            mapOf("q" to NoulQuestion("Is it urgent?")),
        )

        assertEquals("jev-1.13.0", result.model)
        assertEquals("gen-abc123", result.id)
        assertEquals("TypeSafe", result.provider)
        assertEquals(318, result.usage?.inputTokens)
        assertEquals(0.0000134, result.usage?.cost)

        assertEquals(NoulAnswer(0.95), result.answers["is_urgent"])

        val department = result.answers["department"] as ChoiceAnswer
        assertEquals("billing", department.choice)
        assertEquals(0.88, department.probabilities["billing"])
        assertEquals(0.81, department.confidence)

        val frustration = result.answers["frustration"] as ScoreAnswer
        assertEquals(1.05, frustration.score)
        assertEquals("Very angry", frustration.legend["2"])
        assertEquals(0.92, frustration.confidence)
    }

    @Test
    fun `ignores unknown fields so a provider addition does not break parsing`() {
        val transport = ok(
            """{"model":"jev-1.13.0","answers":{},"new_field":{"nested":true}}""",
        )

        val result = client(transport).decide(
            ClassifiedState(DataClassification.Internal, "state"),
            mapOf("q" to NoulQuestion("Is it urgent?")),
        )

        assertTrue(result.answers.isEmpty())
    }

    @Test
    fun `fails with the status when the endpoint rejects the request`() {
        val transport = RecordingTransport(
            TransportResponse(429, """{"error":{"message":"rate limited"}}"""),
        )

        val failure = assertFailsWith<JudgmentRequestException> {
            client(transport).decide(
                ClassifiedState(DataClassification.Internal, "state"),
                mapOf("q" to NoulQuestion("Is it urgent?")),
            )
        }

        assertEquals(429, failure.statusCode)
        assertTrue(failure.message!!.contains("429"))
    }

    @Test
    fun `rejects an empty question set before any request is made`() {
        val transport = ok("""{"model":"jev-1.13.0","answers":{}}""")

        assertFailsWith<IllegalArgumentException> {
            client(transport).decide(ClassifiedState(DataClassification.Internal, "state"), emptyMap())
        }

        assertEquals(0, transport.calls)
    }
}
