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
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
        const val DEFAULT_MODEL = "typesafe/jev-1.13"

        fun fromEnvironment(env: (String) -> String? = System::getenv): DecisionModelConfig {
            fun value(name: String): String? = env(name)?.takeIf { it.isNotBlank() }

            return DecisionModelConfig(
                endpoint = value("DECISION_MODEL_ENDPOINT") ?: DEFAULT_ENDPOINT,
                model = value("DECISION_MODEL") ?: DEFAULT_MODEL,
                apiKey =
                    value("OPENROUTER_API_KEY")
                        ?: throw IllegalStateException("OPENROUTER_API_KEY is not set"),
            )
        }
    }
}
