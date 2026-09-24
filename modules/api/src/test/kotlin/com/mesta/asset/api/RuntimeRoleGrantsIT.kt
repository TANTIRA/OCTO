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

/**
 * Reproduces the deployed role split from issue #17: Flyway runs as a non-superuser migration role
 * and the api connects as a separate runtime role, the way `infra/docker-compose.yml` wires
 * `DB_MIGRATION_USER` and `DB_USER`. The other ITs run everything as one superuser, so a missing
 * grant is invisible to them. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RuntimeRoleGrantsIT {
    @Test
    fun `the runtime role can insert into and read every append-only table`() {
        for ((table, insert) in INSERTS) {
            execute(insert)
            assertThat(single("select count(*) from mesta.$table"))
                .describedAs("rows the runtime role can read in mesta.$table")
                .isNotEqualTo(0L)
        }
    }

    @Test
    fun `update and delete are refused on privilege before the append-only trigger fires`() {
        execute(INSERTS.getValue("ledger_event"))
        for (sql in listOf("update mesta.ledger_event set actor = 'tampered'", "delete from mesta.ledger_event")) {
            assertThatThrownBy { execute(sql) }
                .describedAs(sql)
                .isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo(INSUFFICIENT_PRIVILEGE) }
        }
    }

    @Test
    fun `the runtime role has no privilege on the Flyway history`() {
        assertThat(
            single(
                "select bool_or(has_table_privilege('mesta.flyway_schema_history', p)) " +
                    "from unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) p",
            ),
        ).isEqualTo(false)
    }

    private fun execute(sql: String) {
        runtime.createStatement().use { it.execute(sql) }
    }

    private fun single(sql: String): Any? =
        runtime.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getObject(1)
            }
        }

    private companion object {
        const val MIGRATOR = "mesta_migrator"
        const val RUNTIME = "mesta_app"

        /** SQLSTATE for a missing privilege. The append-only trigger raises 23001 instead. */
        const val INSUFFICIENT_PRIVILEGE = "42501"

        val INSERTS =
            mapOf(
                "ledger_event" to
                    """
                    insert into mesta.ledger_event
                        (flow_type, monetary_amount, currency_code, occurred_at,
                         source_system, actor, ingestion_run_id, correlation_id)
                    values ('contribution', 1000000.00, 'USD', now(),
                            'test', 'integration-test', gen_random_uuid(), gen_random_uuid())
                    """.trimIndent(),
                "document_classification" to
                    """
                    insert into mesta.document_classification
                        (document_sha256, document_type, confidence, distribution, requires_review,
                         model_version, source_system, actor, ingestion_run_id, correlation_id)
                    values (repeat('a', 64), 'pitch-deck', 0.9, '{}', false,
                            'test', 'test', 'integration-test', gen_random_uuid(), gen_random_uuid())
                    """.trimIndent(),
                "claim_assessment" to
                    """
                    insert into mesta.claim_assessment
                        (claim_text, support_probability, support_threshold, review_band, supported,
                         requires_review, model_version, source_system, actor, ingestion_run_id, correlation_id)
                    values ('Revenue grew 21% year over year.', 0.7, 0.5, 0.15, true,
                            false, 'test', 'test', 'integration-test', gen_random_uuid(), gen_random_uuid())
                    """.trimIndent(),
            )

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("admin")
                .withPassword("admin")

        /** Set up once per class: roles are cluster-wide, so a second setup would fail on "already exists". */
        val runtime: Connection by lazy {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
                admin.createStatement().use { statement ->
                    statement.execute("create role $MIGRATOR login password '$MIGRATOR'")
                    statement.execute("grant create on database ${postgres.databaseName} to $MIGRATOR")
                    statement.execute("create role $RUNTIME login password '$RUNTIME'")
                }
            }
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, MIGRATOR, MIGRATOR)
                .locations("classpath:db/migration")
                .schemas("mesta")
                .placeholders(mapOf("runtime_role" to RUNTIME))
                .load()
                .migrate()
            DriverManager.getConnection(postgres.jdbcUrl, RUNTIME, RUNTIME)
        }
    }
}
