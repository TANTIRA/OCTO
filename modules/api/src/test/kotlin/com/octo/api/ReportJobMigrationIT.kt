package com.octo.api

import com.octo.workflow.report.ReportType
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
 * Runs the real Flyway migrations and exercises the V13 `octo.report_job` invariants (#105): the status
 * machine new -> executing -> done | error enforced by the trigger, outcome columns matching the status,
 * immutable request columns, one approval task after done, and no delete. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReportJobMigrationIT {
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

    /** JUnit builds one instance per test and the tenant table is append-only, so the tenant is created once and found afterwards. */
    private val tenant by lazy {
        query(
            """
            with created as (
                insert into octo.tenant (slug, display_name, source_system, correlation_id)
                values ('acme', 'Acme', 'test', gen_random_uuid())
                on conflict (slug) do nothing
                returning id)
            select id from created union all select id from octo.tenant where slug = 'acme'
            """.trimIndent(),
        )
    }

    @Test
    fun `migration creates the table, its transition trigger and the queue index`() {
        assertThat(count("select count(*) from pg_tables where schemaname = 'octo' and tablename = 'report_job'")).isEqualTo(1)
        assertThat(count("select count(*) from pg_trigger where tgname = 'report_job_transition'")).isEqualTo(1)
        assertThat(count("select count(*) from pg_indexes where indexname = 'report_job_queue'")).isEqualTo(1)
    }

    @Test
    fun `a job runs new to executing to done, once, and its request never changes`() {
        val id = job()
        // new -> done skips executing
        assertThatThrownBy { set(id, "status = 'done', result = '{}'") }.isInstanceOf(SQLException::class.java)
        claim(id)
        assertThatThrownBy { set(id, "status = 'done'") }.isInstanceOf(SQLException::class.java) // done needs a result
        assertThatThrownBy { set(id, "measures = '{tvpi}'") }.isInstanceOf(SQLException::class.java) // request is immutable
        set(id, "status = 'done', result = '{\"tvpi\": 1.5}', artifact_sha256 = repeat('a', 64), claim_token = null, claimed_until = null")
        assertThatThrownBy { set(id, "status = 'executing'") }.isInstanceOf(SQLException::class.java) // terminal
        assertThatThrownBy { set(id, "result = '{}'") }.isInstanceOf(SQLException::class.java) // done rows are frozen
        assertThatThrownBy { execute("delete from octo.report_job where id = '$id'") }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `an error needs a message, an artifact needs done, and an approval task comes once after done`() {
        val id = job()
        claim(id)
        assertThatThrownBy { set(id, "status = 'error', error = ' '") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { set(id, "artifact_sha256 = repeat('a', 64)") }.isInstanceOf(SQLException::class.java)
        val task = task()
        assertThatThrownBy { set(id, "approval_task_id = '$task'") }.isInstanceOf(SQLException::class.java) // not done yet
        set(id, "status = 'error', error = 'engine refused the series', claim_token = null, claimed_until = null")
        assertThatThrownBy { job(type = "risk") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { job(measures = "{tvpi,\"\"}") }.isInstanceOf(SQLException::class.java)

        val done = job()
        claim(done)
        set(done, "status = 'done', result = '{}', claim_token = null, claimed_until = null")
        set(done, "approval_task_id = '$task'")
        assertThatThrownBy { set(done, "approval_task_id = '${task()}'") }.isInstanceOf(SQLException::class.java) // only one
    }

    /** #488: the API accepted lp-report but V29's check rejected it. Every type the code can submit must land. */
    @Test
    fun `every report type the code submits passes the job and schedule type checks`() {
        for (type in ReportType.entries) {
            assertThat(job(type = type.wireValue)).`as`("report_job accepts %s", type.wireValue).isNotNull()
            if (type.schedulable) {
                execute(schedule(type.wireValue))
            } else {
                assertThatThrownBy { execute(schedule(type.wireValue)) }
                    .`as`("report_schedule rejects on-demand %s", type.wireValue)
                    .isInstanceOf(SQLException::class.java)
            }
        }
    }

    private fun schedule(type: String) =
        """
        insert into octo.report_schedule (tenant_id, name, report_type, position_source_type, position_source_id, cron, next_run_at)
        values ('$tenant', 'guard', '$type', 'fund', 'fund-1', '0 0 8 * * MON', now())
        """.trimIndent()

    private fun job(
        type: String = "performance",
        measures: String = "{tvpi,irr}",
    ): UUID =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    insert into octo.report_job (tenant_id, report_type, position_source_type, position_source_id, measures, requested_by, correlation_id)
                    values (?, ?, 'inline-series', 'fund-1', ?::text[], 'analyst-1', gen_random_uuid())
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    listOf(tenant, type, measures).forEachIndexed { i, v -> statement.setObject(i + 1, v) }
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
                }
        }

    private fun task(): UUID =
        query(
            """
            insert into octo.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id)
            values ('approval', 'report-job', 'r', 'analyst-1', 'test', gen_random_uuid()) returning id
            """.trimIndent(),
        )

    private fun claim(id: UUID) =
        set(id, "status = 'executing', claim_token = gen_random_uuid(), claimed_until = clock_timestamp() + interval '5 minutes'")

    private fun set(
        id: UUID,
        assignment: String,
    ) = execute("update octo.report_job set $assignment where id = '$id'")

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
                .withDatabaseName("octo")
                .withUsername("octo")
                .withPassword("octo")
    }
}
