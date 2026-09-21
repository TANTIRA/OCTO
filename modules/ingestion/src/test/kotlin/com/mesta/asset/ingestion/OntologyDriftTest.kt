package com.mesta.asset.ingestion

import com.mesta.asset.ingestion.classification.DocumentClassificationCriteria
import com.mesta.asset.ingestion.classification.DocumentType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the enum and the criteria map against the ontology. A schema change that adds or renames a
 * document type fails here rather than silently mislabelling documents in production.
 */
class OntologyDriftTest {
    private val ontology: String =
        File(
            System.getProperty("ontology.file")
                ?: error("ontology.file system property is not set"),
        ).readText()

    @Test
    fun `document type enumeration matches the ontology`() {
        assertEquals(
            attributeValues("document-type").toSet(),
            DocumentType.entries.map { it.wireValue }.toSet(),
        )
    }

    @Test
    fun `every document type is an option in the question`() {
        assertEquals(
            DocumentType.entries.map { it.wireValue }.toSet(),
            DocumentClassificationCriteria.CRITERIA.keys,
        )
    }

    @Test
    fun `every document type has a description for the model`() {
        assertTrue(
            DocumentClassificationCriteria.CRITERIA.values.all { !it.isNullOrBlank() },
            "every option needs a description, including the escape hatch",
        )
    }

    private fun attributeValues(name: String): List<String> {
        val declaration =
            Regex("""attribute\s+$name,\s+value\s+string\s+@values\(([^)]*)\)""")
                .find(ontology)

        assertTrue(declaration != null, "attribute '$name' with @values not found in the ontology")

        return Regex("\"([^\"]+)\"")
            .findAll(declaration.groupValues[1])
            .map { it.groupValues[1] }
            .toList()
    }
}
