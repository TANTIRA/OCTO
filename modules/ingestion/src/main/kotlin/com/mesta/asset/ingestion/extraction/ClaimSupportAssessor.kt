package com.mesta.asset.ingestion.extraction

import com.mesta.asset.controlpanel.judgment.ClassifiedState
import com.mesta.asset.controlpanel.judgment.DecisionLineage
import com.mesta.asset.controlpanel.judgment.JudgmentClient
import com.mesta.asset.controlpanel.judgment.noulAnswer
import kotlin.math.abs

/**
 * The support probability is the value written to `extracted-claim.confidence-level`; the caller
 * stores it alongside the citation, never in place of one.
 */
data class ClaimSupport(
    val probability: Double,
    val supported: Boolean,
    val requiresReview: Boolean,
    val lineage: DecisionLineage,
)

class ClaimSupportAssessor(
    private val client: JudgmentClient,
    private val policy: ClaimSupportPolicy = ClaimSupportPolicy(),
) {
    fun assess(
        state: ClassifiedState,
        questionId: String = DEFAULT_QUESTION_ID,
    ): ClaimSupport {
        val result = client.decide(
            state,
            mapOf(questionId to ClaimSupportCriteria.QUESTION),
        )
        val probability = result.noulAnswer(questionId).noul

        return ClaimSupport(
            probability = probability,
            supported = probability >= policy.supportThreshold,
            requiresReview = abs(probability - policy.supportThreshold) <= policy.reviewBand,
            lineage = result.lineage,
        )
    }

    companion object {
        const val DEFAULT_QUESTION_ID = "claim_supported"
    }
}
