package com.mesta.asset.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.SQLException
import java.util.UUID

/**
 * Runs the real Flyway migrations and exercises the V4 `mesta.valuation_event` invariants: append-only,
 * non-negative NAV, ontology-mirrored valuation methods, and replay protection. Same shape as
 * `LedgerMigrationIT`. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class ValuationMigrationIT {
    private val dataSource by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("mesta")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    @Test
    fun `migration creates the valuation table and its append-only trigger`() {
        assertThat(count("select count(*) from pg_tables where schemaname = 'mesta' and tablename = 'valuation_event'")).isEqualTo(1)
        assertThat(count("select count(*) from pg_trigger where tgname = 'valuation_event_append_only'")).isEqualTo(1)
    }

    @Test
    fun `update is rejected by the append-only trigger`() {
        val id = valuation("100", "2024-06-30")
        assertThatThrownBy { execute("update mesta.valuation_event set monetary_amount = 1 where id = '$id'") }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("append-only")
    }

    @Test
    fun `negative nav, unknown method and replayed source records are rejected`() {
        assertThatThrownBy { valuation("-1", "2024-06-30") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { valuation("1", "2024-06-30", method = "vibes") }.isInstanceOf(SQLException::class.java)
        val externalId = "nav-${UUID.randomUUID()}"
        valuation("1", "2024-06-30", externalId = externalId)
        assertThatThrownBy { valuation("1", "2024-06-30", externalId = externalId) }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `superseding without a rationale is rejected`() {
        val original = valuation("100", "2024-06-30")
        assertThatThrownBy { valuation("101", "2024-06-30", supersedes = original, rationale = null) }
            .isInstanceOf(SQLException::class.java)
    }

    private fun valuation(
        amount: String,
        asOf: String,
        method: String = "mark-to-model",
        externalId: String? = null,
        supersedes: UUID? = null,
        rationale: String? = supersedes?.let { "Restated after audit." },
    ): UUID =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    insert into mesta.valuation_event
                        (external_id, monetary_amount, currency_code, as_of_date, valuation_method, supersedes_id,
                         rationale, source_system, actor, ingestion_run_id, correlation_id)
                    values (?, ?, 'USD', ?::date, ?, ?, ?, 'test', 'integration-test', gen_random_uuid(), gen_random_uuid())
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    listOf(externalId, BigDecimal(amount), asOf, method, supersedes, rationale)
                        .forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
                }
        }

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun count(sql: String): Int =
        dataSource.connection.use { connection ->
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
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")
    }
}
