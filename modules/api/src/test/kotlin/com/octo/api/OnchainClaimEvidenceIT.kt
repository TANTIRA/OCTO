package com.mesta.asset.api

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.mesta.asset.ingestion.onchain.EvidenceKind
import com.mesta.asset.ingestion.onchain.OnchainEvidence
import com.mesta.asset.ingestion.onchain.persistence.JdbcOnchainStagingStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** V16 evidence staging on the real schema: insert, dedupe on the query identity, append-only. */
@Testcontainers(disabledWithoutDocker = true)
class OnchainClaimEvidenceIT {
    private val migrated by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("mesta")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        Unit
    }

    private val store by lazy {
        migrated
        JdbcOnchainStagingStore(dataSource())
    }

    @Test
    fun `evidence stages once per query identity and cannot be mutated`() {
        val evidence =
            OnchainEvidence(
                externalId = "solana:treasury-balance:${addr()}:1748736000:6000000000000",
                claimRef = "claim-${UUID.randomUUID()}",
                subjectAddress = "7VVA" + "J".repeat(39),
                kind = EvidenceKind.TREASURY_BALANCE,
                observedNumeric = BigDecimal("6000000000000"),
                observedText = null,
                payload = JsonNodeFactory.instance.objectNode().put("lamports", 6_000_000_000_000L),
                asOf = Instant.parse("2025-06-01T00:00:00Z"),
            )
        val run = UUID.randomUUID()

        assertThat(store.insertEvidence(listOf(evidence), run, UUID.randomUUID(), "test")).isEqualTo(1)
        assertThat(store.insertEvidence(listOf(evidence), run, UUID.randomUUID(), "test")).isZero()

        dataSource().connection.use { c ->
            c.prepareStatement("update mesta.onchain_claim_evidence set observed_text = 'mutated'").use { s ->
                assertThatThrownBy { s.executeUpdate() }.hasMessageContaining("append-only")
            }
        }
    }

    private fun dataSource() =
        run {
            migrated
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        }

    private fun addr() =
        "9ZZZ" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .replace(Regex("[0OIl]"), "B")
                .take(39)

    private companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")
    }
}
