package com.mesta.asset.ingestion.persistence

import com.mesta.asset.ingestion.classification.DocumentClassification
import com.mesta.asset.ingestion.extraction.ClaimSupport
import com.mesta.asset.ingestion.extraction.ClaimSupportPolicy
import java.util.UUID

/**
 * Who produced a staged decision row. Mirrors the audit tail on `mesta.ledger_event` so an
 * interrupted ingestion run can replay safely: `(source_system, external_id)` is unique, so a
 * replayed record is rejected instead of duplicated.
 */
data class Provenance(
    val sourceSystem: String,
    val actor: String,
    val ingestionRunId: UUID,
    val correlationId: UUID,
    val externalId: String? = null,
)

/**
 * Staging for document-type decisions (`mesta.document_classification`). The row is append-only;
 * a corrected classification is a new row linked by [supersedesId], which the database requires
 * a [rationale] for.
 */
interface DocumentClassificationStore {
    fun record(
        documentSha256: String,
        classification: DocumentClassification,
        provenance: Provenance,
        supersedesId: UUID? = null,
        rationale: String? = null,
    ): UUID
}

/**
 * Staging for claim-support decisions (`mesta.claim_assessment`). The [policy] is persisted with
 * the row so the verdict stays reproducible after thresholds change.
 */
interface ClaimAssessmentStore {
    fun record(
        claimText: String,
        sourceDocumentSha256: String?,
        support: ClaimSupport,
        policy: ClaimSupportPolicy,
        provenance: Provenance,
        supersedesId: UUID? = null,
        rationale: String? = null,
    ): UUID
}
