package com.mesta.asset.ingestion.classification

import com.mesta.asset.controlpanel.judgment.ChoiceAnswer
import com.mesta.asset.controlpanel.judgment.ChoiceQuestion
import com.mesta.asset.controlpanel.judgment.ClassifiedState
import com.mesta.asset.controlpanel.judgment.DataClassification
import com.mesta.asset.controlpanel.judgment.JudgmentResult
import com.mesta.asset.controlpanel.judgment.NoulAnswer
import com.mesta.asset.controlpanel.judgment.UnexpectedAnswerException
import com.mesta.asset.ingestion.StubJudgmentClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun choiceResult(
    choice: String,
    confidence: Double,
    probabilities: Map<String, Double> = mapOf(choice to confidence),
) = JudgmentResult(
    model = "jev-1.13.0",
    id = "gen-1",
    provider = "TypeSafe",
    answers = mapOf("document_type" to ChoiceAnswer(choice, probabilities, confidence)),
)

class DocumentClassifierTest {

    private val state = ClassifiedState(DataClassification.Internal, "synthetic pitch deck text")

    @Test
    fun `maps a confident answer to a document type`() {
        val classification = DocumentClassifier(StubJudgmentClient(choiceResult("pitch-deck", 0.91)))
            .classify(state)

        assertEquals(DocumentType.PITCH_DECK, classification.documentType)
        assertEquals(0.91, classification.confidence)
        assertFalse(classification.requiresReview)
    }

    @Test
    fun `carries the lineage of the decision`() {
        val classification = DocumentClassifier(StubJudgmentClient(choiceResult("memo", 0.8)))
            .classify(state)

        assertEquals("jev-1.13.0", classification.lineage.model)
        assertEquals("TypeSafe", classification.lineage.provider)
        assertEquals("gen-1", classification.lineage.requestId)
    }

    @Test
    fun `asks a choice question whose options are the ontology enumeration`() {
        val client = StubJudgmentClient(choiceResult("memo", 0.8))
        DocumentClassifier(client).classify(state)

        val question = client.lastQuestions[DocumentClassifier.DEFAULT_QUESTION_ID] as ChoiceQuestion
        assertEquals(DocumentClassificationCriteria.INSTRUCTIONS, question.instructions)
        assertEquals(DocumentType.entries.map { it.wireValue }.toSet(), question.criteria.keys)
    }

    @Test
    fun `passes the declared classification through unchanged`() {
        val client = StubJudgmentClient(choiceResult("memo", 0.8))
        DocumentClassifier(client).classify(state)

        assertEquals(DataClassification.Internal, client.lastState?.classification)
    }

    @Test
    fun `confidence below the threshold routes to review`() {
        val classification = DocumentClassifier(StubJudgmentClient(choiceResult("financials", 0.42)))
            .classify(state)

        assertEquals(DocumentType.FINANCIALS, classification.documentType)
        assertTrue(classification.requiresReview)
    }

    @Test
    fun `confidence at the threshold is accepted`() {
        val classification = DocumentClassifier(
            StubJudgmentClient(choiceResult("financials", DocumentClassificationCriteria.MIN_CONFIDENCE)),
        ).classify(state)

        assertFalse(classification.requiresReview)
    }

    @Test
    fun `an option outside the enumeration falls back to other and routes to review`() {
        val classification = DocumentClassifier(StubJudgmentClient(choiceResult("spreadsheet", 0.95)))
            .classify(state)

        assertEquals(DocumentType.OTHER, classification.documentType)
        assertTrue(classification.requiresReview)
    }

    @Test
    fun `keeps the full distribution for the stored decision`() {
        val classification = DocumentClassifier(
            StubJudgmentClient(
                choiceResult(
                    "tear-sheet",
                    0.7,
                    mapOf("tear-sheet" to 0.7, "memo" to 0.2, "other" to 0.1),
                ),
            ),
        ).classify(state)

        assertEquals(0.2, classification.probabilities[DocumentType.MEMO])
        assertEquals(3, classification.probabilities.size)
    }

    @Test
    fun `rejects an answer of the wrong type`() {
        val client = StubJudgmentClient(
            JudgmentResult(
                model = "jev-1.13.0",
                answers = mapOf("document_type" to NoulAnswer(0.9)),
            ),
        )

        assertFailsWith<UnexpectedAnswerException> {
            DocumentClassifier(client).classify(state)
        }
    }

    @Test
    fun `rejects a missing answer`() {
        val client = StubJudgmentClient(JudgmentResult(model = "jev-1.13.0", answers = emptyMap()))

        assertFailsWith<UnexpectedAnswerException> {
            DocumentClassifier(client).classify(state)
        }
    }
}
