package com.mesta.asset.controlpanel.judgment

import com.fasterxml.jackson.annotation.JsonProperty

data class TokenUsage(
    @get:JsonProperty("input_tokens") val inputTokens: Int,
    @get:JsonProperty("output_tokens") val outputTokens: Int,
    val cost: Double? = null,
)

internal data class ProviderRouting(
    @get:JsonProperty("allow_fallbacks") val allowFallbacks: Boolean,
)

internal data class DecisionsRequest(
    val model: String,
    val state: Any,
    val questions: Map<String, JudgmentQuestion>,
    val provider: ProviderRouting? = null,
    @get:JsonProperty("session_id") val sessionId: String? = null,
    val user: String? = null,
)

internal data class DecisionsResponse(
    val model: String,
    val answers: Map<String, JudgmentAnswer>,
    val usage: TokenUsage? = null,
    val id: String? = null,
    val provider: String? = null,
)

/**
 * A decision outcome with the lineage needed to record it: the model that answered, the provider
 * that served it, and the request id.
 */
data class JudgmentResult(
    val model: String,
    val answers: Map<String, JudgmentAnswer>,
    val usage: TokenUsage? = null,
    val id: String? = null,
    val provider: String? = null,
) {
    val lineage: DecisionLineage get() = DecisionLineage(model, provider, id)
}

data class DecisionLineage(
    val model: String,
    val provider: String? = null,
    val requestId: String? = null,
)

class UnexpectedAnswerException(
    questionId: String,
    actual: JudgmentAnswer?,
) : IllegalStateException(
        "question '$questionId' did not return the expected answer type (got ${actual?.type ?: "nothing"})",
    )

fun JudgmentResult.choiceAnswer(questionId: String): ChoiceAnswer =
    answers[questionId] as? ChoiceAnswer
        ?: throw UnexpectedAnswerException(questionId, answers[questionId])

fun JudgmentResult.noulAnswer(questionId: String): NoulAnswer =
    answers[questionId] as? NoulAnswer
        ?: throw UnexpectedAnswerException(questionId, answers[questionId])
