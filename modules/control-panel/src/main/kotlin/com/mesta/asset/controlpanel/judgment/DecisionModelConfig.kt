package com.mesta.asset.controlpanel.judgment

data class DecisionModelConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val allowFallbacks: Boolean = false,
) {
    init {
        require(endpoint.isNotBlank()) { "endpoint is required" }
        require(model.isNotBlank()) { "model is required" }
        require(apiKey.isNotBlank()) { "apiKey is required" }
    }

    override fun toString(): String =
        "DecisionModelConfig(endpoint=$endpoint, model=$model, apiKey=redacted, allowFallbacks=$allowFallbacks)"

    companion object {
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/api/alpha/decisions"
        const val DEFAULT_MODEL = "typesafe/jev-1.13"

        fun fromEnvironment(env: (String) -> String? = System::getenv): DecisionModelConfig =
            DecisionModelConfig(
                endpoint = env("DECISION_MODEL_ENDPOINT") ?: DEFAULT_ENDPOINT,
                model = env("DECISION_MODEL") ?: DEFAULT_MODEL,
                apiKey = env("OPENROUTER_API_KEY")
                    ?: throw IllegalStateException("OPENROUTER_API_KEY is not set"),
            )
    }
}
