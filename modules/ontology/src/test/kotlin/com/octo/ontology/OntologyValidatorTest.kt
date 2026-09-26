package com.mesta.asset.ontology

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CI gate for the investment ontology: RDF syntax, SHACL conformance on the valid sample, and a
 * named breach for every constraint in the negative fixture.
 *
 * The negative assertions are exact on purpose. If a shape is weakened or deleted, the
 * corresponding message disappears and this test fails instead of the gate going quiet.
 */
class OntologyValidatorTest {
    private val ontologyDir: Path =
        Path.of(
            System.getProperty("ontology.dir")
                ?: error("ontology.dir system property is not set — see modules/ontology/build.gradle.kts"),
        )

    private val validator = OntologyValidator.loadDefault(ontologyDir)

    @Test
    fun `ontology and shapes parse and declare shapes`() {
        assertTrue(validator.shapesDeclared > 0, "expected at least one SHACL shape")
    }

    @Test
    fun `valid sample graphs conform to the shapes`() {
        val samples = ttlFiles(ontologyDir.resolve("samples/valid"))
        assertTrue(samples.isNotEmpty(), "expected at least one valid sample graph")
        for (sample in samples) {
            val outcome = validator.validate(sample)
            assertTrue(outcome.conforms, "$sample must conform but reported: ${outcome.violations}")
            assertEquals(emptyList(), outcome.violations, "$sample must report no violations")
        }
    }

    @Test
    fun `invalid sample graphs are rejected with one readable message per breach`() {
        val samples = ttlFiles(ontologyDir.resolve("samples/invalid"))
        assertTrue(samples.isNotEmpty(), "expected at least one invalid sample graph")
        for (sample in samples) {
            val outcome = validator.validate(sample)
            assertFalse(outcome.conforms, "$sample must not conform")
            assertEquals(EXPECTED_BREACHES, outcome.violations.map { it.message }.toSet(), "breaches in $sample")
            outcome.violations.forEach { violation ->
                assertTrue(violation.focusNode.isNotEmpty(), "every violation must name its focus node")
            }
        }
    }

    private fun ttlFiles(dir: Path): List<Path> = Files.newDirectoryStream(dir, "*.ttl").use { stream -> stream.toList().sorted() }

    private companion object {
        /**
         * One message per deliberate breach in samples/invalid/constraint-breaches.ttl: nine from
         * the schema mirror and six from the policy layer.
         */
        val EXPECTED_BREACHES =
            setOf(
                "fundStatus must be one of the fund-status @values.",
                "lei must match ^[A-Z0-9]{20}$.",
                "A country must have an isoCountryCode matching ^[A-Z]{2}$.",
                "confidenceLevel must be within 0..1.",
                "An extracted claim must be the claim-side of an extraction-source; a claim with no source document is ungrounded.",
                "ownershipPct must be within 0..100.",
                "A commitment must relate exactly one investor (mirrors 'relates investor @card(1)').",
                "occurredAt must be an xsd:dateTime.",
                "A screening decision must record a rationale.",
                "A supersedes link must record a rationale; corrections are never unexplained.",
                "A ledger event must be the event-side of a cash-flow-attribution; an unattributed event cannot be reconciled.",
                "A wallet must have a solanaAddress matching ^[1-9A-HJ-NP-Za-km-z]{32,44}$ (mirrors 'owns solana-address @key').",
                "An evm-wallet must have an evmAddress matching ^0x[0-9a-f]{40}$ (mirrors 'owns evm-address @key').",
                "An instrument flow must carry an instrumentFlowType; an untyped token flow cannot be reconciled.",
                "An instrument flow must be the flow-side of an instrument-flow-of; an unattributed flow cannot be reconciled.",
            )
    }
}
