package com.mesta.asset.ingestion.marketdata.alphavantage

/**
 * Connection details for the Alpha Vantage `GET /query` endpoint. `apiKey` is a Confidential
 * credential: it travels only in the `apikey` query parameter and is redacted from [toString].
 * Requests never log the URL — a logged query string would leak the key.
 */
data class AlphaVantageConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String,
) {
    override fun toString(): String = "AlphaVantageConfig(baseUrl=$baseUrl, apiKey=<redacted>)"

    init {
        require(baseUrl.startsWith("https://")) { "baseUrl must be https" }
        require(apiKey.isNotBlank()) { "apiKey required" }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.alphavantage.co"

        fun fromEnvironment(env: (String) -> String? = System::getenv): AlphaVantageConfig {
            fun value(name: String): String? = env(name)?.takeIf { it.isNotBlank() }

            return AlphaVantageConfig(
                baseUrl = value("ALPHA_VANTAGE_BASE_URL") ?: DEFAULT_BASE_URL,
                apiKey =
                    value("ALPHA_VANTAGE_API_KEY")
                        ?: throw IllegalStateException("ALPHA_VANTAGE_API_KEY is not set"),
            )
        }
    }
}
