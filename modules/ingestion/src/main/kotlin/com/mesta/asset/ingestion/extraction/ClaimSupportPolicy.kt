package com.mesta.asset.ingestion.extraction

import com.mesta.asset.controlpanel.judgment.NoulCriteria
import com.mesta.asset.controlpanel.judgment.NoulQuestion

/**
 * Thresholds live here so a reviewer can see the decision boundary without reading the assessor.
 * The band around the boundary is what routes a marginal claim to a human instead of a verdict.
 */
data class ClaimSupportPolicy(
    val supportThreshold: Double = 0.5,
    val reviewBand: Double = 0.15,
) {
    init {
        require(supportThreshold in 0.0..1.0) { "supportThreshold must be within 0..1" }
        require(reviewBand >= 0.0) { "reviewBand must not be negative" }
    }
}

object ClaimSupportCriteria {

    const val INSTRUCTIONS = "Is the claim supported by the quoted passage from the document?"

    val QUESTION = NoulQuestion(
        instructions = INSTRUCTIONS,
        criteria = NoulCriteria(
            whenTrue = "The quoted passage states the claim, or directly entails it.",
            whenFalse = "The passage does not state the claim, contradicts it, or is unrelated to it.",
        ),
    )
}
