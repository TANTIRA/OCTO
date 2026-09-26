package com.mesta.asset.ingestion.onchain.helius

import com.mesta.asset.ingestion.onchain.FinalityProbe

/**
 * [FinalityProbe] over `getSignatureStatuses` — one batched call per delivery. Only
 * `confirmationStatus == "finalized"` passes; `confirmed`, `processed`, errored and unknown
 * (null) entries all stay out.
 */
class HeliusFinalityProbe(
    private val rpc: HeliusRpcApi,
) : FinalityProbe {
    override fun finalizedSignatures(signatures: Collection<String>): Set<String> {
        if (signatures.isEmpty()) return emptySet()
        val sigs = signatures.toList()
        val values = rpc.signatureStatuses(sigs).path("value")
        return sigs.indices
            .mapNotNull { i -> sigs[i].takeIf { values.path(i).path("confirmationStatus").asText() == "finalized" } }
            .toSet()
    }
}
