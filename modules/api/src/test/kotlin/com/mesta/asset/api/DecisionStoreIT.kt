package com.mesta.asset.api

import com.mesta.asset.controlpanel.judgment.DecisionLineage
import com.mesta.asset.ingestion.classification.DocumentClassification
import com.mesta.asset.ingestion.classification.DocumentType
import com.mesta.asset.ingestion.extraction.ClaimSupport
import com.mesta.asset.ingestion.extraction.ClaimSupportPolicy
import com.mesta.asset.ingestion.persistence.JdbcDecisionStore
import com.mesta.asset.ingestion.persistence.Provenance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.UUID

/**
 * Exercises the decision staging tables end to end: the real Flyway migrations plus real writes
 * through `JdbcDecisionStore`. Same shape as `LedgerMigrationIT` — append-only, replay protection,
 * and the check constraints that mirror the ontology. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class DecisionStoreIT {

    private val store: JdbcDecisionStore by lazy {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("mesta")
            .load()
            .migrate()
        JdbcDecisionStore(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        )
    }

    @Test
    fun `migration creates both staging tables and their append-only triggers`() {
        assertThat(count("select count(*) from information_schema.tables where table_schema = 'mesta' and table_name in ('document_classification', 'claim_assessment')"))
            .describedAs("decision staging tables after migration")
            .isEqualTo(2)
        assertThat(count("select count(*) from pg_trigger where tgname in ('document_classification_append_only', 'claim_assessment_append_only')"))
            .describedAs("append-only triggers after migration")
            .isEqualTo(2)
    }

    @Test
    fun `a document classification round-trips through the staging table`() {
        val id = recordClassification()
        assertThat(count("select count(*) from mesta.document_classification where id = '$id' and document_type = 'pitch-deck' and confidence = 0.82"))
            .isEqualTo(1)
        assertThat(count("select count(*) from mesta.document_classification where id = '$id' and distribution ->> 'pitch-deck' = '0.82'"))
            .describedAs("the full distribution must persist, not only the argmax")
            .isEqualTo(1)
    }

    @Test
    fun `a claim assessment round-trips with its policy snapshot`() {
        val id = recordClaim()
        assertThat(count("select count(*) from mesta.claim_assessment where id = '$id' and supported = true and support_threshold = 0.5 and review_band = 0.15"))
            .isEqualTo(1)
    }

    @Test
    fun `an unknown document type is rejected`() {
        assertThatThrownBy {
            recordClassification(documentType = DocumentType.PITCH_DECK, rawTypeOverride = "whitepaper")
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a malformed document sha256 is rejected`() {
        assertThatThrownBy {
            recordClassification(documentSha256 = "not-a-sha")
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `update is rejected by the append-only trigger`() {
        val id = recordClassification()
        assertThatThrownBy {
            dataSource().connection.use { connection ->
                connection.createStatement().use {
                    it.executeUpdate("update mesta.document_classification set confidence = 0.1 where id = '$id'")
                }
            }
        }.isInstanceOf(SQLException::class.java).hasMessageContaining("append-only")
    }

    @Test
    fun `a replayed source record does not insert twice`() {
        val externalId = "doc-${UUID.randomUUID()}"
        recordClassification(externalId = externalId)
        assertThatThrownBy { recordClassification(externalId = externalId) }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `superseding a classification without a rationale is rejected`() {
        val original = recordClassification()
        assertThatThrownBy {
            recordClassification(supersedesId = original, rationale = null)
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `superseding a classification with a rationale is accepted`() {
        val original = recordClassification()
        assertThat(recordClassification(supersedesId = original, rationale = "Re-run with the corrected extraction."))
            .isNotNull()
    }

    private fun recordClassification(
        documentSha256: String = "a".repeat(64),
        documentType: DocumentType = DocumentType.PITCH_DECK,
        rawTypeOverride: String? = null,
        externalId: String? = null,
        supersedesId: UUID? = null,
        rationale: String? = null,
    ): UUID {
        val classification = DocumentClassification(
            documentType = documentType,
            confidence = 0.82,
            probabilities = mapOf(documentType to 0.82, DocumentType.OTHER to 0.18),
            requiresReview = false,
            lineage = DecisionLineage(model = "typesafe/jev-1.13", provider = "openrouter", requestId = "req-test"),
        )
        if (rawTypeOverride != null) {
            // Bypasses the enum to prove the check constraint mirrors the ontology @values.
            dataSource().connection.use { connection ->
                connection.prepareStatement(
                    "insert into mesta.document_classification (document_sha256, document_type, confidence, distribution, requires_review, model_version, source_system, actor, ingestion_run_id, correlation_id) values (?, ?, 0.5, '{}', false, 'test', 'test', 'it', ?, ?)",
                ).use { statement ->
                    statement.setString(1, documentSha256)
                    statement.setString(2, rawTypeOverride)
                    statement.setObject(3, UUID.randomUUID())
                    statement.setObject(4, UUID.randomUUID())
                    statement.execute()
                }
            }
            return UUID(0, 0)
        }
        return store.record(documentSha256, classification, provenance(externalId), supersedesId, rationale)
    }

    private fun recordClaim(): UUID =
        store.record(
            claimText = "Revenue grew 21% year over year.",
            sourceDocumentSha256 = "b".repeat(64),
            support = ClaimSupport(
                probability = 0.7,
                supported = true,
                requiresReview = false,
                lineage = DecisionLineage(model = "typesafe/jev-1.13", provider = "openrouter", requestId = "req-claim"),
            ),
            policy = ClaimSupportPolicy(),
            provenance = provenance(),
        )

    private fun provenance(externalId: String? = null) = Provenance(
        sourceSystem = "test",
        actor = "integration-test",
        ingestionRunId = UUID.randomUUID(),
        correlationId = UUID.randomUUID(),
        externalId = externalId,
    )

    private fun dataSource() = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun count(sql: String): Int =
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("mesta")
            .withUsername("mesta")
            .withPassword("mesta")
    }
}
