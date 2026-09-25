package com.mesta.asset.ingestion.onchain

import java.math.BigDecimal

/** How a declared metric compares to what the claim asserts. */
enum class ClaimComparator(
    val db: String,
) {
    AT_LEAST("at-least"),
    AT_MOST("at-most"),
    EQUALS("equals"),
}

enum class ClaimVerdict {
    SUPPORTED,
    REFUTED,
    INCONCLUSIVE,
}

/** The metric a claim asserts — what evidence kind and the threshold/direction. */
data class DeclaredMetric(
    val kind: EvidenceKind,
    val comparator: ClaimComparator,
    val value: BigDecimal,
)

/** The verdict plus the observation that produced it — the lineage the wedge needs. */
data class ClaimVerification(
    val claimRef: String,
    val metric: DeclaredMetric,
    val observed: BigDecimal?,
    val verdict: ClaimVerdict,
    val evidenceExternalId: String?,
)

/**
 * Deterministic claim-vs-evidence check (issue #116). No model is involved: the verdict is a
 * numeric comparison against the newest evidence of the declared kind, which is also why
 * prompt-injection inside claim text is structurally inert here — this class never sees the
 * text, only the metric the extraction pipeline declared.
 *
 * INCONCLUSIVE is the honest answer for missing or non-numeric evidence; it is not a failure
 * to be hidden — a deck's claim we cannot check stays visibly unchecked.
 */
class ClaimVerifier(
    private val equalityTolerance: Double = 0.02,
) {
    fun verify(
        claimRef: String,
        declared: DeclaredMetric,
        evidence: List<OnchainEvidence>,
    ): ClaimVerification {
        val latest = evidence.filter { it.kind == declared.kind }.maxByOrNull { it.asOf }
        val observed = latest?.observedNumeric
        if (observed == null) {
            return ClaimVerification(claimRef, declared, null, ClaimVerdict.INCONCLUSIVE, latest?.externalId)
        }
        val verdict =
            when (declared.comparator) {
                ClaimComparator.AT_LEAST -> if (observed >= declared.value) ClaimVerdict.SUPPORTED else ClaimVerdict.REFUTED
                ClaimComparator.AT_MOST -> if (observed <= declared.value) ClaimVerdict.SUPPORTED else ClaimVerdict.REFUTED
                ClaimComparator.EQUALS -> {
                    val tolerance =
                        declared
                            .value
                            .abs()
                            .multiply(BigDecimal.valueOf(equalityTolerance))
                            .max(BigDecimal.ONE)
                    if (observed.subtract(declared.value).abs() <= tolerance) {
                        ClaimVerdict.SUPPORTED
                    } else {
                        ClaimVerdict.REFUTED
                    }
                }
            }
        return ClaimVerification(claimRef, declared, observed, verdict, latest.externalId)
    }
}
