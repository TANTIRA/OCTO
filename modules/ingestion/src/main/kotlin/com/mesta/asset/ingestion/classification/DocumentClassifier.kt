package com.mesta.asset.ingestion.classification

import com.mesta.asset.controlpanel.judgment.ClassifiedState
import com.mesta.asset.controlpanel.judgment.DecisionLineage
import com.mesta.asset.controlpanel.judgment.JudgmentClient
import com.mesta.asset.controlpanel.judgment.choiceAnswer

data class DocumentClassification(
    val documentType: DocumentType,
    val confidence: Double,
    val probabilities: Map<DocumentType, Double>,
    val requiresReview: Boolean,
    val lineage: DecisionLineage,
)

class DocumentClassifier(
    private val client: JudgmentClient,
    private val minConfidence: Double = DocumentClassificationCriteria.MIN_CONFIDENCE,
) {
    fun classify(
        state: ClassifiedState,
        questionId: String = DEFAULT_QUESTION_ID,
    ): DocumentClassification {
        val result =
            client.decide(
                state,
                mapOf(questionId to DocumentClassificationCriteria.question()),
            )
        val answer = result.choiceAnswer(questionId)
        val documentType = DocumentType.fromWireValue(answer.choice)

        return DocumentClassification(
            documentType = documentType ?: DocumentType.OTHER,
            confidence = answer.confidence,
            probabilities =
                answer.probabilities
                    .mapNotNull { (option, probability) ->
                        DocumentType.fromWireValue(option)?.let { it to probability }
                    }.toMap(),
            requiresReview = documentType == null || answer.confidence < minConfidence,
            lineage = result.lineage,
        )
    }

    companion object {
        const val DEFAULT_QUESTION_ID = "document_type"
    }
}
