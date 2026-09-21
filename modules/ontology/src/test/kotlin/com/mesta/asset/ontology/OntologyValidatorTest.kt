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

    private val ontologyDir: Path = Path.of(
        System.getProperty("ontology.dir")
            ?: error("ontology.dir system property is not set — see modules/ontology/build.gradle.kts"),
    )

    private val validator = OntologyValidator.load(
        ontologyDir.resolve("mesta-investment-owl.ttl"),
        ontologyDir.resolve("mesta-investment-shacl.ttl"),
    )

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

    private fun ttlFiles(dir: Path): List<Path> =
        Files.newDirectoryStream(dir, "*.ttl").use { stream -> stream.toList().sorted() }

    private companion object {
        val EXPECTED_BREACHES = setOf(
            "fundStatus must be one of the fund-status @values.",
            "An LEI must be 20 upper-case alphanumerics (mirrors the lei @regex).",
            "confidenceLevel must be within 0..1.",
            "A ledger event must carry a 3-letter currencyCode.",
            "A ledger event that supersedes an earlier event must record a rationale (mirrors 'supersedes owns rationale').",
            "A screening decision must record a rationale.",
        )
    }
}
