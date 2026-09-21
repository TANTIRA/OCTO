package com.mesta.asset.ontology

import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.shacl.ShaclValidator
import org.apache.jena.shacl.Shapes
import org.apache.jena.shacl.ValidationReport
import org.apache.jena.shacl.validation.Severity
import java.nio.file.Path

/**
 * One SHACL constraint breach, shaped for logs and API responses.
 *
 * @property message the `sh:message` declared on the failing shape.
 * @property focusNode IRI of the offending entity.
 * @property path the property path that failed, when the constraint is on a property shape.
 */
data class Violation(
    val message: String,
    val focusNode: String,
    val path: String?,
)

/**
 * Result of validating one data graph against the SHACL shapes.
 *
 * @property conforms true when the report contains no `sh:Violation`. SHACL `sh:Info`
 *   and `sh:Warning` results do not affect conformance.
 * @property violations the `sh:Violation`-severity results, deduplicated by message and focus node.
 */
data class ValidationOutcome(
    val conforms: Boolean,
    val violations: List<Violation>,
)

/**
 * Deterministic OWL/SHACL validation for the Mesta-Asset investment ontology.
 *
 * Loads `ontology/mesta-investment-owl.ttl` and `ontology/mesta-investment-shacl.ttl`, then
 * validates data graphs with RDFS inference so the class hierarchy in the ontology
 * (`FundManager` ⊑ `Organization` ⊑ `Party`) is visible to shapes targeting a supertype.
 *
 * Validation is structural and reproducible: it is a CI gate and a pre-write check, never a
 * source of financial values. IBOR derivation and reported metrics stay deterministic.
 */
class OntologyValidator private constructor(
    private val ontology: Model,
    private val shapes: Shapes,
) {

    /** Number of SHACL shapes parsed from the shapes graph. */
    val shapesDeclared: Int get() = shapes.numShapes()

    /**
     * Validate a Turtle file against the shapes.
     *
     * @param dataPath path to the data graph; a parse error fails loudly rather than silently passing.
     */
    fun validate(dataPath: Path): ValidationOutcome =
        validate(RDFDataMgr.loadModel(dataPath.toUri().toString()))

    /** Validate an already-loaded model against the shapes. */
    fun validate(data: Model): ValidationOutcome {
        val graph = ModelFactory.createRDFSModel(ontology, data).graph
        return toOutcome(ShaclValidator.get().validate(shapes, graph))
    }

    private fun toOutcome(report: ValidationReport): ValidationOutcome {
        val violations = report.entries
            .filter { Severity.Violation == it.severity() }
            .map { entry ->
                Violation(
                    message = entry.message()?.trim().orEmpty().ifEmpty { entry.toString() },
                    focusNode = entry.focusNode()?.let { if (it.isURI) it.uri else it.toString() }.orEmpty(),
                    path = entry.resultPath()?.toString(),
                )
            }
            .distinctBy { it.message to it.focusNode }
        return ValidationOutcome(report.conforms(), violations)
    }

    companion object {
        /**
         * Load the ontology and shapes graphs.
         *
         * @throws org.apache.jena.riot.RiotException when either file is not valid Turtle,
         *   which is the RDF syntax check required by `AGENTS.md`.
         */
        fun load(ontologyPath: Path, shapesPath: Path): OntologyValidator {
            val ontology = RDFDataMgr.loadModel(ontologyPath.toUri().toString())
            val shapesModel = RDFDataMgr.loadModel(shapesPath.toUri().toString())
            return OntologyValidator(ontology, Shapes.parse(shapesModel))
        }
    }
}
