package com.mesta.asset.ingestion.extraction

import com.mesta.asset.controlpanel.judgment.ChoiceAnswer
import com.mesta.asset.controlpanel.judgment.ClassifiedState
import com.mesta.asset.controlpanel.judgment.DataClassification
import com.mesta.asset.controlpanel.judgment.JudgmentResult
import com.mesta.asset.controlpanel.judgment.NoulAnswer
import com.mesta.asset.controlpanel.judgment.NoulQuestion
import com.mesta.asset.controlpanel.judgment.UnexpectedAnswerException
import com.mesta.asset.ingestion.StubJudgmentClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun noulResult(probability: Double) = JudgmentResult(
    model = "jev-1.13.0",
    id = "gen-2",
    provider = "TypeSafe",
    answers = mapOf("claim_supported" to NoulAnswer(probability)),
)

class ClaimSupportAssessorTest {

    private val state = ClassifiedState(
        DataClassification.Internal,
        "claim: revenue grew 40% in FY24 | passage: synthetic financial summary",
    )

    @Test
    fun `a supported claim carries its probability`() {
        val support = ClaimSupportAssessor(StubJudgmentClient(noulResult(0.93))).assess(state)

        assertEquals(0.93, support.probability)
        assertTrue(support.supported)
        assertFalse(support.requiresReview)
    }

    @Test
    fun `an unsupported claim is not marked supported`() {
        val support = ClaimSupportAssessor(StubJudgmentClient(noulResult(0.08))).assess(state)

        assertEquals(0.08, support.probability)
        assertFalse(support.supported)
        assertFalse(support.requiresReview)
    }

    @Test
    fun `the support threshold is inclusive`() {
        val atThreshold = ClaimSupportAssessor(
            StubJudgmentClient(noulResult(ClaimSupportPolicy().supportThreshold)),
        ).assess(state)
        val below = ClaimSupportAssessor(StubJudgmentClient(noulResult(0.49))).assess(state)

        assertTrue(atThreshold.supported)
        assertFalse(below.supported)
    }

    @Test
    fun `a marginal claim routes to review instead of a bare verdict`() {
        val support = ClaimSupportAssessor(StubJudgmentClient(noulResult(0.52))).assess(state)

        assertTrue(support.supported)
        assertTrue(support.requiresReview)
    }

    @Test
    fun `a clear answer outside the band does not route to review`() {
        val support = ClaimSupportAssessor(StubJudgmentClient(noulResult(0.9))).assess(state)

        assertFalse(support.requiresReview)
    }

    @Test
    fun `carries the lineage of the decision`() {
        val support = ClaimSupportAssessor(StubJudgmentClient(noulResult(0.9))).assess(state)

        assertEquals("jev-1.13.0", support.lineage.model)
        assertEquals("gen-2", support.lineage.requestId)
    }

    @Test
    fun `asks a yes or no question with both criteria described`() {
        val client = StubJudgmentClient(noulResult(0.9))
        ClaimSupportAssessor(client).assess(state)

        val question = client.lastQuestions[ClaimSupportAssessor.DEFAULT_QUESTION_ID] as NoulQuestion
        assertEquals(ClaimSupportCriteria.INSTRUCTIONS, question.instructions)
        assertNotNull(question.criteria?.whenTrue)
        assertNotNull(question.criteria?.whenFalse)
    }

    @Test
    fun `rejects an answer of the wrong type`() {
        val client = StubJudgmentClient(
            JudgmentResult(
                model = "jev-1.13.0",
                answers = mapOf("claim_supported" to ChoiceAnswer("yes", mapOf("yes" to 1.0), 0.9)),
            ),
        )

        assertFailsWith<UnexpectedAnswerException> {
            ClaimSupportAssessor(client).assess(state)
        }
    }

    @Test
    fun `policy rejects an out of range threshold`() {
        assertFailsWith<IllegalArgumentException> { ClaimSupportPolicy(supportThreshold = 1.5) }
        assertFailsWith<IllegalArgumentException> { ClaimSupportPolicy(supportThreshold = -0.1) }
        assertFailsWith<IllegalArgumentException> { ClaimSupportPolicy(reviewBand = -0.1) }
    }

    @Test
    fun `a custom policy moves the decision boundary`() {
        val strict = ClaimSupportAssessor(
            StubJudgmentClient(noulResult(0.75)),
            ClaimSupportPolicy(supportThreshold = 0.8, reviewBand = 0.05),
        ).assess(state)

        assertFalse(strict.supported)
    }
}
