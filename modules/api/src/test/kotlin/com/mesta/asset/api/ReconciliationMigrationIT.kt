package com.mesta.asset.api

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
 * Runs the real Flyway migrations and exercises the V15 `mesta.reconciliation_break` invariants (#107): the sides
 * a kind must have, append-only, and one task per break key across runs. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationMigrationIT {
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

    /** JUnit builds one instance per test and the tenant table is append-only, so the tenant is created once and found afterwards. */
    private val tenant by lazy {
        query(
            """
            with created as (
                insert into mesta.tenant (slug, display_name, source_system, correlation_id)
                values ('acme', 'Acme', 'test', gen_random_uuid())
                on conflict (slug) do nothing
                returning id)
            select id from created union all select id from mesta.tenant where slug = 'acme'
            """.trimIndent(),
        )
    }

    @Test
    fun `migration creates the table, its append-only trigger and the one-task index`() {
        assertThat(count("select count(*) from pg_tables where schemaname = 'mesta' and tablename = 'reconciliation_break'")).isEqualTo(1)
        assertThat(count("select count(*) from pg_trigger where tgname = 'reconciliation_break_append_only'")).isEqualTo(1)
        assertThat(count("select count(*) from pg_indexes where indexname = 'reconciliation_break_one_task'")).isEqualTo(1)
    }

    @Test
    fun `each kind carries the sides it must, and rows are never edited`() {
        val event = ledgerEvent()
        breakRow("missing-in-ibor", sourceRef = "t-1")
        breakRow("missing-in-source", ledgerEventId = event)
        breakRow("amount-mismatch", sourceRef = "t-1", ledgerEventId = event)
        assertThatThrownBy { breakRow("missing-in-ibor", ledgerEventId = event) }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { breakRow("missing-in-source", sourceRef = "t-2") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { breakRow("amount-mismatch", sourceRef = "t-1") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { breakRow("fuzzy", sourceRef = "t-1") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            breakRow("missing-in-ibor", sourceRef = "t-1", ledgerEventId = UUID.randomUUID())
        }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            execute(
                "delete from mesta.reconciliation_break where source_ref = 't-1'",
            )
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `one task per break key across runs, while untasked repeats of a finding stay recordable`() {
        val event = ledgerEvent()
        val task = task()
        breakRow("date-mismatch", sourceRef = "t-7", ledgerEventId = event, taskId = task)
        assertThatThrownBy {
            breakRow("date-mismatch", sourceRef = "t-7", ledgerEventId = event, taskId = task())
        }.isInstanceOf(SQLException::class.java)
        breakRow("date-mismatch", sourceRef = "t-7", ledgerEventId = event) // the next run records the finding again without a task
        breakRow("amount-mismatch", sourceRef = "t-7", ledgerEventId = event, taskId = task()) // another kind is another break
        breakRow("missing-in-ibor", sourceRef = "t-8", taskId = task())
        assertThatThrownBy { breakRow("missing-in-ibor", sourceRef = "t-8", taskId = task()) }.isInstanceOf(SQLException::class.java)
    }

    private fun breakRow(
        kind: String,
        sourceRef: String? = null,
        ledgerEventId: UUID? = null,
        taskId: UUID? = null,
    ) = dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                """
                insert into mesta.reconciliation_break (tenant_id, run_id, kind, source_system, source_ref, ledger_event_id, task_id, correlation_id)
                values (?, gen_random_uuid(), ?, 'admin-a', ?, ?, ?, gen_random_uuid())
                """.trimIndent(),
            ).use { statement ->
                listOf(tenant, kind, sourceRef, ledgerEventId, taskId).forEachIndexed { i, v -> statement.setObject(i + 1, v) }
                statement.executeUpdate()
            }
    }

    private fun ledgerEvent(): UUID =
        query(
            """
            insert into mesta.ledger_event (flow_type, monetary_amount, currency_code, occurred_at, source_system, actor, ingestion_run_id, correlation_id)
            values ('contribution', -100, 'USD', now(), 'admin-a', 'it', gen_random_uuid(), gen_random_uuid()) returning id
            """.trimIndent(),
        )

    private fun task(): UUID =
        query(
            """
            insert into mesta.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id)
            values ('evidence-request', 'reconciliation-break', 'x', 'recon-runner', 'test', gen_random_uuid()) returning id
            """.trimIndent(),
        )

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun query(sql: String): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getObject(1, UUID::class.java)
                }
            }
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
