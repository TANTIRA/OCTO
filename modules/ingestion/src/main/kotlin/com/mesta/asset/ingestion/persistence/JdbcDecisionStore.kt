package com.mesta.asset.ingestion.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.classification.DocumentClassification
import com.mesta.asset.ingestion.extraction.ClaimSupport
import com.mesta.asset.ingestion.extraction.ClaimSupportPolicy
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC writer for the append-only decision staging tables. Constraint violations surface as
 * `SQLException` — an unknown document type, a malformed hash, or a missing rationale is a caller
 * bug, not a retryable condition.
 */
class JdbcDecisionStore(
    private val dataSource: DataSource,
) : DocumentClassificationStore,
    ClaimAssessmentStore {
    private val json = ObjectMapper()

    override fun record(
        documentSha256: String,
        classification: DocumentClassification,
        provenance: Provenance,
        supersedesId: UUID?,
        rationale: String?,
    ): UUID {
        val sql =
            """
            insert into mesta.document_classification
                (external_id, document_sha256, document_type, confidence, distribution,
                 requires_review, model_provider, model_version, decision_request_id,
                 supersedes_id, rationale, source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning id
            """.trimIndent()
        val distribution =
            json.writeValueAsString(
                classification.probabilities.mapKeys { it.key.wireValue },
            )
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, provenance.externalId)
                statement.setString(2, documentSha256)
                statement.setString(3, classification.documentType.wireValue)
                statement.setDouble(4, classification.confidence)
                statement.setString(5, distribution)
                statement.setBoolean(6, classification.requiresReview)
                statement.setString(7, classification.lineage.provider)
                statement.setString(8, classification.lineage.model)
                statement.setString(9, classification.lineage.requestId)
                statement.setNullableUuid(10, supersedesId)
                statement.setString(11, rationale)
                statement.setString(12, provenance.sourceSystem)
                statement.setString(13, provenance.actor)
                statement.setObject(14, provenance.ingestionRunId)
                statement.setObject(15, provenance.correlationId)
                return statement.returnedId()
            }
        }
    }

    override fun record(
        claimText: String,
        sourceDocumentSha256: String?,
        support: ClaimSupport,
        policy: ClaimSupportPolicy,
        provenance: Provenance,
        supersedesId: UUID?,
        rationale: String?,
    ): UUID {
        val sql =
            """
            insert into mesta.claim_assessment
                (external_id, claim_text, source_document_sha256, support_probability,
                 support_threshold, review_band, supported, requires_review,
                 model_provider, model_version, decision_request_id,
                 supersedes_id, rationale, source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning id
            """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, provenance.externalId)
                statement.setString(2, claimText)
                statement.setString(3, sourceDocumentSha256)
                statement.setDouble(4, support.probability)
                statement.setDouble(5, policy.supportThreshold)
                statement.setDouble(6, policy.reviewBand)
                statement.setBoolean(7, support.supported)
                statement.setBoolean(8, support.requiresReview)
                statement.setString(9, support.lineage.provider)
                statement.setString(10, support.lineage.model)
                statement.setString(11, support.lineage.requestId)
                statement.setNullableUuid(12, supersedesId)
                statement.setString(13, rationale)
                statement.setString(14, provenance.sourceSystem)
                statement.setString(15, provenance.actor)
                statement.setObject(16, provenance.ingestionRunId)
                statement.setObject(17, provenance.correlationId)
                return statement.returnedId()
            }
        }
    }

    private fun PreparedStatement.setNullableUuid(
        index: Int,
        value: UUID?,
    ) {
        if (value == null) setNull(index, Types.OTHER) else setObject(index, value)
    }

    private fun PreparedStatement.returnedId(): UUID =
        executeQuery().use { rows ->
            check(rows.next()) { "insert returned no id" }
            rows.getObject(1, UUID::class.java)
        }
}
