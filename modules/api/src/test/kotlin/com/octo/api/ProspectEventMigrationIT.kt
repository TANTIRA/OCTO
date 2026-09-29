package com.octo.api

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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V36: `octo.prospect_event_rules()` enforces `Prospect.next()` for a caller that writes rows
 * directly, without the Kotlin state machine — the same contract `WorkflowMigrationIT` pins down
 * for V7's task rules. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProspectEventMigrationIT {
    private val dataSource by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("octo")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    private val t0 = "2026-09-01T00:00:00Z"

    @Test
    fun `the trigger accepts exactly the walk replay accepts`() {
        val prospect = newProspect()
        rawEvent(prospect, "advanced", "sourced", "screening", at = "2026-09-01T00:00:01Z")
        rawEvent(prospect, "advanced", "screening", "due-diligence", at = "2026-09-01T00:00:02Z")
        rawEvent(prospect, "advanced", "due-diligence", "ic-review", at = "2026-09-01T00:00:03Z")
        rawEvent(
            prospect,
            "invested",
            "ic-review",
            "invested",
            at = "2026-09-01T00:00:04Z",
            rationale = "conviction in the corridor thesis",
            taskId = newTask(),
        )

        assertThat(count("select count(*) from octo.prospect_event where prospect_id = '$prospect'")).isEqualTo(4)

        // a second prospect takes the other terminal exit from an open stage
        val passed = newProspect()
        rawEvent(passed, "advanced", "sourced", "screening", at = "2026-09-01T00:00:01Z")
        rawEvent(passed, "passed", "screening", "passed", at = "2026-09-01T00:00:02Z", rationale = "thesis drift")
        assertThat(count("select count(*) from octo.prospect_event where prospect_id = '$passed'")).isEqualTo(2)
    }

    @Test
    fun `terminal stages accept nothing and stage_from must name the true stage`() {
        val prospect = newProspect()
        rawEvent(prospect, "passed", "sourced", "passed", at = "2026-09-01T00:00:01Z", rationale = "off mandate")

        assertRefused(CHECK_VIOLATION) {
            rawEvent(prospect, "advanced", "passed", "screening", at = "2026-09-01T00:00:02Z")
        }
        assertRefused(CHECK_VIOLATION) {
            rawEvent(prospect, "passed", "sourced", "passed", at = "2026-09-01T00:00:02Z", rationale = "again")
        }
        assertThat(count("select count(*) from octo.prospect_event where prospect_id = '$prospect'")).isEqualTo(1)
    }

    @Test
    fun `advances move one stage and invested needs ic-review`() {
        val prospect = newProspect()

        assertRefused(CHECK_VIOLATION) {
            rawEvent(prospect, "advanced", "sourced", "due-diligence", at = "2026-09-01T00:00:01Z")
        }
        assertRefused(CHECK_VIOLATION) {
            rawEvent(prospect, "advanced", "screening", "due-diligence", at = "2026-09-01T00:00:01Z")
        }
        assertRefused(CHECK_VIOLATION) {
            rawEvent(prospect, "invested", "sourced", "invested", at = "2026-09-01T00:00:01Z", taskId = newTask())
        }
        assertThat(count("select count(*) from octo.prospect_event where prospect_id = '$prospect'")).isEqualTo(0)
    }

    @Test
    fun `events stay in business-time order, including against registration`() {
        val prospect = newProspect()
        rawEvent(prospect, "advanced", "sourced", "screening", at = "2026-09-01T00:00:01Z")

        assertRefused(CHECK_VIOLATION) {
            rawEvent(prospect, "advanced", "screening", "due-diligence", at = "2026-09-01T00:00:00Z")
        }
        // even the first event cannot predate the prospect's own registration
        assertRefused(CHECK_VIOLATION) {
            rawEvent(newProspect(), "advanced", "sourced", "screening", at = "2026-08-31T23:59:59Z")
        }
    }

    @Test
    fun `a losing append race fails instead of interleaving`() {
        val prospect = newProspect()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts =
                (1..2).map {
                    pool.submit<Unit> {
                        rawEvent(prospect, "advanced", "sourced", "screening", at = "2026-09-01T00:00:01Z")
                    }
                }
            val results = attempts.map { runCatching { it.get(30, TimeUnit.SECONDS) } }
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(results.count { it.isFailure }).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
        assertThat(count("select count(*) from octo.prospect_event where prospect_id = '$prospect'")).isEqualTo(1)
    }

    private fun newProspect(): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "insert into octo.tenant (slug, display_name, source_system, correlation_id) " +
                            "values ('t-${UUID.randomUUID().toString().take(8)}', 'T', 'test', gen_random_uuid()) returning id",
                    ).use { rows ->
                        rows.next()
                        val tenant = rows.getObject(1, UUID::class.java)
                        statement
                            .executeQuery(
                                "insert into octo.prospect (id, tenant_id, name, source, registered_at, source_system, actor, correlation_id) " +
                                    "values (gen_random_uuid(), '$tenant', 'acme', 'manual', '$t0', 'test', 'test', gen_random_uuid()) returning id",
                            ).use { created ->
                                created.next()
                                created.getObject(1, UUID::class.java)
                            }
                    }
            }
        }

    /** A workflow_task row an `invested` event can name — V19's FK needs it to exist. */
    private fun newTask(): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "insert into octo.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id) " +
                            "values ('approval', 'prospect', 'x', 'test', 'test', gen_random_uuid()) returning id",
                    ).use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
            }
        }

    /** A raw event insert that bypasses the Kotlin machine — what the trigger exists to police. */
    private fun rawEvent(
        prospect: UUID,
        type: String,
        stageFrom: String,
        stageTo: String,
        at: String,
        rationale: String? = null,
        taskId: UUID? = null,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "insert into octo.prospect_event (prospect_id, event_type, stage_from, stage_to, actor, rationale, occurred_at, task_id, correlation_id) " +
                        "values (?, ?, ?, ?, 'someone', ?, ?::timestamptz, ?, gen_random_uuid())",
                ).use { statement ->
                    statement.setObject(1, prospect)
                    statement.setString(2, type)
                    statement.setString(3, stageFrom)
                    statement.setString(4, stageTo)
                    statement.setString(5, rationale)
                    statement.setString(6, at)
                    statement.setObject(7, taskId)
                    statement.executeUpdate()
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

    private fun assertRefused(
        sqlState: String,
        block: () -> Unit,
    ) {
        assertThatThrownBy(block)
            .isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo(sqlState) }
    }

    private companion object {
        const val CHECK_VIOLATION = "23514"

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("octo")
                .withUsername("octo")
                .withPassword("octo")
    }
}
