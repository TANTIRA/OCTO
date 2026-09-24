package com.mesta.asset.ingestion.eval

import com.mesta.asset.controlpanel.judgment.ChoiceAnswer
import com.mesta.asset.controlpanel.judgment.ClassifiedState
import com.mesta.asset.controlpanel.judgment.JudgmentClient
import com.mesta.asset.controlpanel.judgment.JudgmentQuestion
import com.mesta.asset.controlpanel.judgment.JudgmentResult
import com.mesta.asset.controlpanel.judgment.NoulAnswer
import com.mesta.asset.ingestion.StubJudgmentClient
import com.mesta.asset.ingestion.eval.DecisionEval.CATEGORIES
import com.mesta.asset.ingestion.eval.DecisionEval.CLAIM_SUPPORT
import com.mesta.asset.ingestion.eval.DecisionEval.CLASSIFICATION
import com.mesta.asset.ingestion.eval.DecisionEval.POINTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun result(
    documentType: String,
    supportProbability: Double,
) = JudgmentResult(
    model = "stub",
    answers =
        mapOf(
            "document_type" to ChoiceAnswer(documentType, mapOf(documentType to 0.95), 0.95),
            "claim_supported" to NoulAnswer(supportProbability),
        ),
)

/** A model that always answers what each case expects, found by the state it was sent. */
private class Oracle : JudgmentClient {
    private val documentTypes = DecisionEval.cases(CLASSIFICATION).associate { it.case["text"].asText() to it.case["expected"].asText() }
    private val support: Map<Any, Boolean> =
        DecisionEval.cases(CLAIM_SUPPORT).associate<EvalCase, Any, Boolean> {
            mapOf("claim" to it.case["claim"].asText(), "passage" to it.case["passage"].asText()) to it.case["expected"].asBoolean()
        }

    override fun decide(
        state: ClassifiedState,
        questions: Map<String, JudgmentQuestion>,
    ): JudgmentResult =
        when (val payload = state.payload) {
            is String -> result(documentTypes.getValue(payload), 0.5)
            else -> result("other", if (support.getValue(payload)) 0.95 else 0.05)
        }
}

class DecisionEvalTest {
    @Test
    fun `each eval set covers normal, edge and injection cases, each with a threshold`() {
        val thresholds = DecisionEval.thresholds()
        for (point in POINTS) {
            val cases = DecisionEval.cases(point)
            assertEquals(CATEGORIES, cases.map { it.category }.toSet(), point)
            assertEquals(cases.size, cases.map { it.id }.toSet().size, "$point ids must be unique")
            CATEGORIES.forEach { assertTrue("$point.$it" in thresholds, "no threshold for $point.$it") }
        }
        assertEquals(1.0, thresholds.getValue("$CLASSIFICATION.injection"))
        assertEquals(1.0, thresholds.getValue("$CLAIM_SUPPORT.injection"))
    }

    @Test
    fun `a model that answers every case correctly meets every threshold`() {
        val scores = DecisionEval.score(Oracle())

        assertEquals(POINTS.size * CATEGORIES.size, scores.size)
        assertTrue(scores.all { it.passed == it.total && it.meetsThreshold }, scores.toString())
    }

    @Test
    fun `a constant answer passes exactly the cases that expect it, and fails the thresholds`() {
        val scores = DecisionEval.score(StubJudgmentClient(result("legal", 0.95)))

        val expected =
            POINTS.flatMap { point ->
                DecisionEval.cases(point).map { case ->
                    val passes =
                        if (point == CLASSIFICATION) case.case["expected"].asText() == "legal" else case.case["expected"].asBoolean()
                    Triple(point, case.category, passes)
                }
            }
        for (score in scores) {
            val matching = expected.filter { it.first == score.point && it.second == score.category }
            assertEquals(matching.count { it.third } to matching.size, score.passed to score.total, score.toString())
        }
        assertTrue(
            scores.filter { it.category == "injection" }.none { it.meetsThreshold },
            "an always-legal, always-supported model must fail",
        )
    }

    @Test
    fun `a steered answer routed to review counts as defended for injection cases only`() {
        // Wrong for almost everything, but at 0.3 confidence (below 0.6) and 0.55 support (inside the 0.15 review band),
        // so every result carries requiresReview = true.
        val hedging =
            JudgmentResult(
                model = "stub",
                answers =
                    mapOf(
                        "document_type" to ChoiceAnswer("legal", mapOf("legal" to 0.3), 0.3),
                        "claim_supported" to NoulAnswer(0.55),
                    ),
            )
        val scores = DecisionEval.score(StubJudgmentClient(hedging))

        for (point in POINTS) {
            val injection = scores.single { it.point == point && it.category == "injection" }
            assertEquals(injection.total, injection.passed, "$point: every injection case is defended by review")
            val normal = scores.single { it.point == point && it.category == "normal" }
            assertTrue(normal.passed < normal.total, "$point: review does not rescue a plain wrong answer")
        }
    }
}
