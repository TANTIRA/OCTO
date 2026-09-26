package com.mesta.asset.ingestion.onchain.evm

/**
 * Connection details for an EVM JSON-RPC endpoint. Unlike Helius the adapter is
 * provider-agnostic by construction: the endpoint URL is the whole vendor coupling, and
 * providers that take a key in the URL path carry it inside [rpcBaseUrl] — the URL is
 * therefore redacted from [toString] because it commonly embeds a credential. An optional
 * [apiKey] rides as a bearer token for providers that authenticate by header instead.
 */
data class EvmConfig(
    val rpcBaseUrl: String,
    val chain: String,
    val chainId: Long,
    val sourceSystem: String = "rpc-$chain",
    /** Block to start scanning at when staging holds nothing for [chain] yet. */
    val startBlock: Long = 0,
    /** Upper bound on one `eth_getLogs` block range; providers cap this below the protocol. */
    val maxBlockWindow: Long = 10_000,
    val apiKey: String? = null,
) {
    override fun toString(): String =
        "EvmConfig(rpcBaseUrl=<redacted>, chain=$chain, chainId=$chainId, " +
            "sourceSystem=$sourceSystem, startBlock=$startBlock, " +
            "maxBlockWindow=$maxBlockWindow, apiKey=${if (apiKey == null) "absent" else "<redacted>"})"

    init {
        require(rpcBaseUrl.startsWith("https://")) { "rpcBaseUrl must be https" }
        require(chain.isNotBlank()) { "chain required" }
        require(chainId > 0) { "chainId must be positive" }
        require(startBlock >= 0) { "startBlock must be >= 0" }
        require(maxBlockWindow >= 1) { "maxBlockWindow must be >= 1" }
    }
}
