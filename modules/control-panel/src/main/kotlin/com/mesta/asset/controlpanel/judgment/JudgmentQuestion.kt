package com.mesta.asset.controlpanel.judgment

import com.fasterxml.jackson.annotation.JsonProperty

sealed interface JudgmentQuestion {
    val type: String
    val instructions: String
}

data class NoulCriteria(
    @get:JsonProperty("true") val whenTrue: String? = null,
    @get:JsonProperty("false") val whenFalse: String? = null,
)

data class NoulQuestion(
    override val instructions: String,
    val criteria: NoulCriteria? = null,
) : JudgmentQuestion {
    override val type: String get() = "noul"
}

data class ChoiceQuestion(
    override val instructions: String,
    val criteria: Map<String, String?>,
) : JudgmentQuestion {
    override val type: String get() = "choice"
}

data class ScoreQuestion(
    override val instructions: String,
    val criteria: List<String>,
) : JudgmentQuestion {
    override val type: String get() = "score"
}
