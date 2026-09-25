package com.mesta.asset.ingestion.onchain.helius

/**
 * Connection details for Helius read-only endpoints. `apiKey` is a Confidential credential: it
 * travels only in the `api-key` query parameter and is redacted from [toString] so logging a
 * config can never leak it.
 */
data class HeliusConfig(
    val rpcBaseUrl: String,
    val walletApiBaseUrl: String,
    val apiKey: String,
    val network: HeliusNetwork,
) {
    override fun toString(): String =
        "HeliusConfig(rpcBaseUrl=$rpcBaseUrl, walletApiBaseUrl=$walletApiBaseUrl, " +
            "apiKey=<redacted>, network=$network)"

    init {
        require(rpcBaseUrl.startsWith("https://")) { "rpcBaseUrl must be https" }
        require(walletApiBaseUrl.startsWith("https://")) { "walletApiBaseUrl must be https" }
        require(apiKey.isNotBlank()) { "apiKey required" }
    }
}

enum class HeliusNetwork {
    MAINNET,
    DEVNET,
}
