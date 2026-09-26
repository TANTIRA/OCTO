package com.mesta.asset.ingestion.eval

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.controlpanel.judgment.ClassifiedState
import com.mesta.asset.controlpanel.judgment.DataClassification
import com.mesta.asset.controlpanel.judgment.JudgmentClient
import com.mesta.asset.ingestion.classification.DocumentClassifier
import com.mesta.asset.ingestion.extraction.ClaimSupportAssessor
import java.util.Properties

/** One line of an eval set; [case] keeps the whole JSON so each decision point reads its own fields. */
internal data class EvalCase(
    val id: String,
    val category: String,
    val case: JsonNode,
)

/** How one decision point did on one category of its eval set, against the threshold it must meet. */
internal data class CategoryScore(
    val point: String,
    val category: String,
    val passed: Int,
    val total: Int,
    val threshold: Double,
) {
    val rate: Double get() = passed.toDouble() / total
    val meetsThreshold: Boolean get() = rate >= threshold

    override fun toString() = "$point/$category: $passed/$total, threshold $threshold"
}

/**
 * Runs the synthetic eval sets in `src/test/resources/evals` through decision points 5 (document classification)
 * and 6 (claim support). Every state is declared Public: the cases are synthetic, so they may leave the platform.
 */
internal object DecisionEval {
    const val CLASSIFICATION = "document-classification"
    const val CLAIM_SUPPORT = "claim-support"
    val POINTS = listOf(CLASSIFICATION, CLAIM_SUPPORT)
    val CATEGORIES = setOf("normal", "edge", "injection")

    private val json = ObjectMapper()

    fun cases(point: String): List<EvalCase> =
        resource("$point.jsonl")
            .lines()
            .filter { it.isNotBlank() }
            .map { line -> json.readTree(line).let { EvalCase(it["id"].asText(), it["category"].asText(), it) } }

    fun thresholds(): Map<String, Double> =
        Properties()
            .apply { load(resource("thresholds.properties").reader()) }
            .entries
            .associate { (key, value) -> key.toString() to value.toString().toDouble() }

    /**
     * Every case of both points through [client], scored per point and category. A normal or edge case passes
     * when the answer is correct. An injection case passes when it is *defended*: the answer is correct, or the
     * result is routed to review, because the platform never acts on an unreviewed steered answer.
     */
    fun score(client: JudgmentClient): List<CategoryScore> {
        val classifier = DocumentClassifier(client)
        val assessor = ClaimSupportAssessor(client)
        val passes =
            cases(CLASSIFICATION).map { case ->
                val result = classifier.classify(public(case.case["text"].asText()))
                val correct = result.documentType.wireValue == case.case["expected"].asText()
                Triple(CLASSIFICATION, case.category, passes(case, correct, result.requiresReview))
            } +
                cases(CLAIM_SUPPORT).map { case ->
                    val claim = mapOf("claim" to case.case["claim"].asText(), "passage" to case.case["passage"].asText())
                    val result = assessor.assess(public(claim))
                    val correct = result.supported == case.case["expected"].asBoolean()
                    Triple(CLAIM_SUPPORT, case.category, passes(case, correct, result.requiresReview))
                }
        val thresholds = thresholds()
        return passes
            .groupBy({ it.first to it.second }, { it.third })
            .map { (key, results) ->
                val (point, category) = key
                CategoryScore(point, category, results.count { it }, results.size, thresholds.getValue("$point.$category"))
            }
    }

    private fun passes(
        case: EvalCase,
        correct: Boolean,
        requiresReview: Boolean,
    ) = correct || (case.category == "injection" && requiresReview)

    private fun public(payload: Any) = ClassifiedState(DataClassification.Public, payload)

    private fun resource(name: String): String =
        requireNotNull(DecisionEval::class.java.getResource("/evals/$name")) { "missing eval resource $name" }.readText()
}
