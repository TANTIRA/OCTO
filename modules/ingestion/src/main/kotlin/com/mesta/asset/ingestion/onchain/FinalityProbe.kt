package com.mesta.asset.ingestion.onchain

import com.mesta.asset.ingestion.onchain.helius.HeliusConfig
import com.mesta.asset.ingestion.onchain.helius.HeliusFinalityProbe
import com.mesta.asset.ingestion.onchain.helius.HeliusNetwork
import com.mesta.asset.ingestion.onchain.helius.HeliusRpcClient

/**
 * Verifies observed finality — the webhook path's honesty gate (#167). A delivery arrives
 * before finalization, so a signature is only safe to stage once the chain reports it
 * `finalized`; anything else (confirmed, processed, unknown) stays out and the poller's
 * finalized-only scan picks the transaction up when it gets there.
 */
fun interface FinalityProbe {
    /** The subset of [signatures] the chain reports as `finalized` right now. */
    fun finalizedSignatures(signatures: Collection<String>): Set<String>

    companion object {
        /**
         * Helius-backed probe — the only seam callers outside `ingestion` may touch, since
         * vendor types stay inside the helius adapter package (ADR-0001).
         */
        fun helius(
            rpcBaseUrl: String,
            apiKey: String,
            devnet: Boolean = false,
        ): FinalityProbe =
            HeliusFinalityProbe(
                HeliusRpcClient(
                    HeliusConfig(
                        rpcBaseUrl = rpcBaseUrl,
                        walletApiBaseUrl = "https://api.helius.xyz",
                        apiKey = apiKey,
                        network = if (devnet) HeliusNetwork.DEVNET else HeliusNetwork.MAINNET,
                    ),
                ),
            )
    }
}
