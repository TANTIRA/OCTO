package com.mesta.asset.controlpanel.judgment

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

class OpenRouterDecisionsClient(
    private val config: DecisionModelConfig,
    private val transport: JudgmentTransport,
    private val objectMapper: ObjectMapper = defaultObjectMapper(),
) : JudgmentClient {

    override fun decide(
        state: ClassifiedState,
        questions: Map<String, JudgmentQuestion>,
    ): JudgmentResult {
        require(questions.isNotEmpty()) { "at least one question is required" }
        if (!state.classification.mayLeavePlatform) {
            throw ConfidentialStateRejectedException(state.classification)
        }

        val request = DecisionsRequest(
            model = config.model,
            state = state.payload,
            questions = questions,
            provider = ProviderRouting(config.allowFallbacks),
        )
        val response = transport.post(
            endpoint = config.endpoint,
            headers = mapOf(
                "Authorization" to "Bearer ${config.apiKey}",
                "Content-Type" to "application/json",
            ),
            body = objectMapper.writeValueAsString(request),
        )
        if (response.statusCode !in 200..299) {
            throw JudgmentRequestException(response.statusCode, response.body.take(MAX_ERROR_BODY))
        }

        val decoded = objectMapper.readValue(response.body, DecisionsResponse::class.java)
        return JudgmentResult(
            model = decoded.model,
            answers = decoded.answers,
            usage = decoded.usage,
            id = decoded.id,
            provider = decoded.provider,
        )
    }

    private companion object {
        const val MAX_ERROR_BODY = 512

        fun defaultObjectMapper(): ObjectMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    }
}
