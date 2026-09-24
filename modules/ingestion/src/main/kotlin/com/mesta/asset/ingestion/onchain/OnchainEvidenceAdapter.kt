package com.mesta.asset.ingestion.onchain

import com.fasterxml.jackson.databind.JsonNode
import com.mesta.asset.ingestion.onchain.helius.HeliusRpcApi
import java.math.BigDecimal
import java.time.Instant

/** The subject an extracted claim points at — what the adapter may verify onchain. */
data class EvidenceSubject(
    val claimRef: String,
    val mintAddress: String? = null,
    val treasuryAddress: String? = null,
)

/**
 * Gathers onchain evidence for an extracted claim's subject (issue #116).
 *
 * Every record is what the chain reported — a query ref as `external_id`, the observed value,
 * and the normalized response payload for lineage. Whether the claim *holds* is a downstream
 * verdict (`ClaimVerifier`); this adapter never judges, and the claim text itself never
 * arrives — [EvidenceSubject.claimRef] is an opaque reference.
 *
 * Honesty limits: `holder-concentration` is the top-20 share of supply (the RPC's
 * `getTokenLargestAccounts` window), not a holder count; `account-activity` is the count of
 * signatures in one bounded history page.
 */
class OnchainEvidenceAdapter(
    private val rpc: HeliusRpcApi,
) {
    fun gather(
        subject: EvidenceSubject,
        chain: String = CHAIN_SOLANA,
        asOf: Instant = Instant.now(),
        activityPageSize: Int = 100,
    ): List<OnchainEvidence> =
        buildList {
            subject.mintAddress?.let { mint ->
                val supply = rpc.tokenSupply(mint)
                add(
                    evidence(
                        subject,
                        EvidenceKind.TOKEN_SUPPLY,
                        mint,
                        supply.path("amount").asText().toBigDecimalOrNull(),
                        supply.path("uiAmountString").asText(null),
                        supply,
                        chain,
                        asOf,
                    ),
                )

                val largest = rpc.tokenLargestAccounts(mint)
                add(
                    evidence(
                        subject,
                        EvidenceKind.HOLDER_CONCENTRATION,
                        mint,
                        topShare(largest, supply),
                        null,
                        largest,
                        chain,
                        asOf,
                    ),
                )
            }
            subject.treasuryAddress?.let { treasury ->
                val lamports = rpc.balance(treasury)
                add(
                    evidence(
                        subject,
                        EvidenceKind.TREASURY_BALANCE,
                        treasury,
                        BigDecimal.valueOf(lamports),
                        null,
                        numericPayload(lamports),
                        chain,
                        asOf,
                    ),
                )

                val signatures = rpc.signaturesForAddress(treasury, activityPageSize)
                add(
                    evidence(
                        subject,
                        EvidenceKind.ACCOUNT_ACTIVITY,
                        treasury,
                        BigDecimal.valueOf(signatures.size().toLong()),
                        null,
                        signatures,
                        chain,
                        asOf,
                    ),
                )
            }
        }

    /** Share of supply held by the largest accounts the RPC reports, 0..1. */
    private fun topShare(
        largest: JsonNode,
        supply: JsonNode,
    ): BigDecimal? {
        val total = supply.path("amount").asText().toBigDecimalOrNull() ?: return null
        if (total.signum() == 0) return null
        val held =
            largest.fold(BigDecimal.ZERO) { acc, account ->
                acc + (account.path("amount").asText().toBigDecimalOrNull() ?: BigDecimal.ZERO)
            }
        return held.divide(total, CONCENTRATION_SCALE, java.math.RoundingMode.HALF_UP)
    }

    private fun numericPayload(lamports: Long): JsonNode =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
            .objectNode()
            .put("lamports", lamports)

    private fun evidence(
        subject: EvidenceSubject,
        kind: EvidenceKind,
        address: String,
        numeric: BigDecimal?,
        text: String?,
        payload: JsonNode,
        chain: String,
        asOf: Instant,
    ): OnchainEvidence {
        val observed = numeric?.toPlainString() ?: text ?: "none"
        return OnchainEvidence(
            externalId = "$chain:${kind.db}:$address:${asOf.epochSecond}:$observed",
            claimRef = subject.claimRef,
            subjectAddress = address,
            kind = kind,
            observedNumeric = numeric,
            observedText = text,
            payload = payload,
            asOf = asOf,
            chain = chain,
        )
    }

    private companion object {
        const val CONCENTRATION_SCALE = 6
    }
}
