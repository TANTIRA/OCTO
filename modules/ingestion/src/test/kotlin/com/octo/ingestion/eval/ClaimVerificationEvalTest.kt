package com.mesta.asset.ingestion.eval

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.mesta.asset.ingestion.onchain.ClaimComparator
import com.mesta.asset.ingestion.onchain.ClaimVerdict
import com.mesta.asset.ingestion.onchain.ClaimVerifier
import com.mesta.asset.ingestion.onchain.DeclaredMetric
import com.mesta.asset.ingestion.onchain.EvidenceKind
import com.mesta.asset.ingestion.onchain.OnchainEvidence
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The claim-verification eval — deterministic, so unlike points 5/6 it runs in CI without a
 * model key. Thresholds sit in `evals/thresholds.properties` next to the LLM points: a
 * deterministic verifier is expected to be exact, so every category demands 1.00.
 */
class ClaimVerificationEvalTest {
    private val mapper = ObjectMapper()
    private val verifier = ClaimVerifier()

    @Test
    fun `claim verification meets every category threshold`() {
        val cases =
            requireNotNull(javaClass.getResource("/evals/claim-verification.jsonl"))
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .map { mapper.readTree(it) }
        val thresholds = DecisionEval.thresholds()

        val scores =
            cases.groupBy { it["category"].asText() }.mapValues { (_, group) ->
                val passed =
                    group.count { case ->
                        val verdict = verify(case)
                        val expected = ClaimVerdict.valueOf(case["expected"].asText().uppercase())
                        if (verdict != expected) {
                            System.err.println("eval miss ${case["id"].asText()}: got $verdict, expected $expected")
                        }
                        verdict == expected
                    }
                Triple(passed, group.size, thresholds.getValue("claim-verification.${group.first()["category"].asText()}"))
            }

        for ((category, score) in scores) {
            val (passed, total, threshold) = score
            assertTrue(
                passed.toDouble() / total >= threshold,
                "claim-verification/$category: $passed/$total below threshold $threshold",
            )
        }
    }

    private fun verify(case: com.fasterxml.jackson.databind.JsonNode): ClaimVerdict {
        val declared = case["declared"]
        val metric =
            DeclaredMetric(
                kind = EvidenceKind.entries.first { it.db == declared["kind"].asText() },
                comparator = ClaimComparator.entries.first { it.db == declared["comparator"].asText() },
                value = BigDecimal(declared["value"].asText()),
            )
        val evidence =
            case["evidence"].map { node ->
                OnchainEvidence(
                    externalId = "solana:${declared["kind"].asText()}:subject:0:${node["observedNumeric"].asText()}",
                    claimRef = case["claimRef"].asText(),
                    subjectAddress = "7VVA" + "H".repeat(39),
                    kind = EvidenceKind.entries.first { it.db == node["kind"].asText() },
                    observedNumeric = node["observedNumeric"]?.let { BigDecimal(it.asText()) },
                    observedText = null,
                    payload = JsonNodeFactory.instance.objectNode(),
                    asOf = Instant.parse("2025-06-01T00:00:00Z"),
                )
            }
        return verifier.verify(case["claimRef"].asText(), metric, evidence).verdict
    }
}
