package com.octo.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.octo.ingestion.onchain.helius.HeliusConfig
import com.octo.ingestion.onchain.helius.HeliusNetwork
import com.octo.ingestion.onchain.helius.HeliusRpcClient

/**
 * Fetches the canonical on-chain transaction for a signature — the webhook honesty gate's other
 * half (#316). [FinalityProbe] proves a signature landed; this proves what actually happened in
 * it. A webhook delivery's own `accountKeys`/balances are never normalized directly — only what
 * this returns is, so a delivery that pairs a real finalized signature with fabricated content
 * cannot promote a transfer that never happened.
 */
fun interface TransactionFetcher {
    /** The canonical `getTransaction` (jsonParsed) result for [signature], or null if the chain has none. */
    fun fetch(signature: String): JsonNode?

    companion object {
        /**
         * Helius-backed fetcher — the only helius touchpoint api is allowed to hold, since vendor
         * types stay inside the helius adapter package (ADR-0001).
         */
        fun helius(
            rpcBaseUrl: String,
            apiKey: String,
            devnet: Boolean = false,
        ): TransactionFetcher {
            val client =
                HeliusRpcClient(
                    HeliusConfig(
                        rpcBaseUrl = rpcBaseUrl,
                        walletApiBaseUrl = "https://api.helius.xyz",
                        apiKey = apiKey,
                        network = if (devnet) HeliusNetwork.DEVNET else HeliusNetwork.MAINNET,
                    ),
                )
            return TransactionFetcher { signature -> client.transaction(signature) }
        }
    }
}
