package com.mesta.asset.controlpanel.judgment

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = NoulAnswer::class, name = "noul"),
    JsonSubTypes.Type(value = ChoiceAnswer::class, name = "choice"),
    JsonSubTypes.Type(value = ScoreAnswer::class, name = "score"),
)
sealed interface JudgmentAnswer {
    val type: String
}

data class NoulAnswer(val noul: Double) : JudgmentAnswer {
    override val type: String get() = "noul"
}

data class ChoiceAnswer(
    val choice: String,
    val probabilities: Map<String, Double>,
    val confidence: Double,
) : JudgmentAnswer {
    override val type: String get() = "choice"
}

data class ScoreAnswer(
    val score: Double,
    val legend: Map<String, String>,
    val probabilities: Map<String, Double>,
    val confidence: Double,
) : JudgmentAnswer {
    override val type: String get() = "score"
}
