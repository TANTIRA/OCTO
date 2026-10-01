package com.octo.ingestion.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.ingestion.classification.DocumentClassification
import com.octo.ingestion.extraction.ClaimSupport
import com.octo.ingestion.extraction.ClaimSupportPolicy
import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC writer for the append-only decision staging tables. Constraint violations surface as
 * `SQLException` — an unknown document type, a malformed hash, or a missing rationale is a caller
 * bug, not a retryable condition. Each write runs scoped to the row's own tenant so the V30
 * `tenant_scope` RLS policy admits it for the non-owner runtime role (#507).
 */
class JdbcDecisionStore(
    private val dataSource: DataSource,
) : DocumentClassificationStore,
    ClaimAssessmentStore {
    private val json = ObjectMapper()

    override fun record(
        tenantId: UUID,
        documentSha256: String,
        classification: DocumentClassification,
        provenance: Provenance,
        supersedesId: UUID?,
        rationale: String?,
    ): UUID {
        val sql =
            """
            insert into octo.document_classification
                (tenant_id, external_id, document_sha256, document_type, confidence, distribution,
                 requires_review, model_provider, model_version, decision_request_id,
                 supersedes_id, rationale, source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning id
            """.trimIndent()
        val distribution =
            json.writeValueAsString(
                classification.probabilities.mapKeys { it.key.wireValue },
            )
        return dataSource.scoped(TenantScope.Tenants(listOf(tenantId))) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, provenance.externalId)
                statement.setString(3, documentSha256)
                statement.setString(4, classification.documentType.wireValue)
                statement.setDouble(5, classification.confidence)
                statement.setString(6, distribution)
                statement.setBoolean(7, classification.requiresReview)
                statement.setString(8, classification.lineage.provider)
                statement.setString(9, classification.lineage.model)
                statement.setString(10, classification.lineage.requestId)
                statement.setNullableUuid(11, supersedesId)
                statement.setString(12, rationale)
                statement.setString(13, provenance.sourceSystem)
                statement.setString(14, provenance.actor)
                statement.setObject(15, provenance.ingestionRunId)
                statement.setObject(16, provenance.correlationId)
                statement.returnedId()
            }
        }
    }

    override fun record(
        tenantId: UUID,
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
            insert into octo.claim_assessment
                (tenant_id, external_id, claim_text, source_document_sha256, support_probability,
                 support_threshold, review_band, supported, requires_review,
                 model_provider, model_version, decision_request_id,
                 supersedes_id, rationale, source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning id
            """.trimIndent()
        return dataSource.scoped(TenantScope.Tenants(listOf(tenantId))) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, provenance.externalId)
                statement.setString(3, claimText)
                statement.setString(4, sourceDocumentSha256)
                statement.setDouble(5, support.probability)
                statement.setDouble(6, policy.supportThreshold)
                statement.setDouble(7, policy.reviewBand)
                statement.setBoolean(8, support.supported)
                statement.setBoolean(9, support.requiresReview)
                statement.setString(10, support.lineage.provider)
                statement.setString(11, support.lineage.model)
                statement.setString(12, support.lineage.requestId)
                statement.setNullableUuid(13, supersedesId)
                statement.setString(14, rationale)
                statement.setString(15, provenance.sourceSystem)
                statement.setString(16, provenance.actor)
                statement.setObject(17, provenance.ingestionRunId)
                statement.setObject(18, provenance.correlationId)
                statement.returnedId()
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
