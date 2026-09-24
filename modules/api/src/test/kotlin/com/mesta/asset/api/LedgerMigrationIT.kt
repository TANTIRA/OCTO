package com.mesta.asset.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/**
 * Runs the real Flyway migrations against a throwaway PostgreSQL and exercises the append-only
 * ledger invariants that `data-security-governance.md` requires.
 *
 * The migrations are loaded from the same `classpath:db/migration` location the application uses, so
 * a migration that fails to package is caught here. Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class LedgerMigrationIT {
    private val connection: Connection by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("mesta")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    @Test
    fun `migration creates the ledger and its append-only trigger`() {
        assertThat(count("select count(*) from information_schema.tables where table_schema = 'mesta' and table_name = 'ledger_event'"))
            .describedAs("mesta.ledger_event after migration")
            .isEqualTo(1)
        assertThat(count("select count(*) from pg_trigger where tgname = 'ledger_event_append_only'"))
            .describedAs("append-only trigger after migration")
            .isEqualTo(1)
    }

    @Test
    fun `a plain event is accepted`() {
        assertThat(insert()).isNotNull()
    }

    @Test
    fun `an unknown flow type is rejected`() {
        assertThatThrownBy { insert(flowType = "closed-beta") }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a malformed currency code is rejected`() {
        assertThatThrownBy { insert(currencyCode = "US") }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `superseding an event without a rationale is rejected`() {
        val original = insert()
        assertThatThrownBy { insert(supersedesId = original, rationale = null) }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `superseding an event with a rationale is accepted`() {
        val original = insert()
        assertThat(insert(supersedesId = original, rationale = "Allocation revised.")).isNotNull()
    }

    @Test
    fun `update is rejected by the append-only trigger`() {
        val id = insert()
        assertThatThrownBy {
            connection.createStatement().use {
                it.executeUpdate("update mesta.ledger_event set rationale = 'rewritten' where id = '$id'")
            }
        }.isInstanceOf(SQLException::class.java)
            .hasMessageContaining("append-only")
    }

    @Test
    fun `delete is rejected by the append-only trigger`() {
        val id = insert()
        assertThatThrownBy {
            connection.createStatement().use {
                it.executeUpdate("delete from mesta.ledger_event where id = '$id'")
            }
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a replayed source record does not insert twice`() {
        val externalId = "src-${UUID.randomUUID()}"
        insert(externalId = externalId)
        assertThatThrownBy { insert(externalId = externalId) }.isInstanceOf(SQLException::class.java)
    }

    private fun count(sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun insert(
        flowType: String = "contribution",
        currencyCode: String = "USD",
        supersedesId: UUID? = null,
        rationale: String? = null,
        externalId: String? = null,
    ): UUID? {
        val sql =
            """
            insert into mesta.ledger_event
                (external_id, flow_type, monetary_amount, currency_code, occurred_at, supersedes_id,
                 rationale, source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, now(), ?, ?, 'test', 'integration-test', ?, ?)
            returning id
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, externalId ?: "ext-${UUID.randomUUID()}")
            statement.setString(2, flowType)
            statement.setBigDecimal(3, java.math.BigDecimal("1000000.00"))
            statement.setString(4, currencyCode)
            if (supersedesId == null) {
                statement.setNull(5, Types.OTHER)
            } else {
                statement.setObject(5, supersedesId)
            }
            statement.setString(6, rationale)
            statement.setObject(7, UUID.randomUUID())
            statement.setObject(8, UUID.randomUUID())
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.getObject(1, UUID::class.java) else null
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
