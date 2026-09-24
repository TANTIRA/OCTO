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
 * Runs the real Flyway migrations and exercises the V9 `mesta.model_run` invariants (#102, methodology §9.6):
 * append-only, json objects only, one immutable version per family and tenant, labelled outputs. Same shape
 * as `ValuationMigrationIT`. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class ModelRunMigrationIT {
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
    fun `migration creates both tables with their append-only triggers`() {
        for (table in listOf("model_run", "model_run_output")) {
            assertThat(count("select count(*) from pg_tables where schemaname = 'mesta' and tablename = '$table'")).isEqualTo(1)
            assertThat(count("select count(*) from pg_trigger where tgname = '${table}_append_only'")).isEqualTo(1)
        }
    }

    @Test
    fun `a version is immutable per family and tenant, and never edited`() {
        val id = run("hmm", "v1")
        assertThatThrownBy { run("hmm", "v1") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { execute("update mesta.model_run set status = 'retired' where id = '$id'") }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("append-only")
        assertThatThrownBy { run("hmm", "v2", supersedes = id, rationale = " ") }.isInstanceOf(SQLException::class.java)
        run("hmm", "v2", supersedes = id, rationale = "re-estimated with 2026Q2 data")
    }

    @Test
    fun `json fields must be objects, status and output kind must be known`() {
        assertThatThrownBy { run("hmm", "v3", parameters = "[1, 2]") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { run("hmm", "v4", status = "live") }.isInstanceOf(SQLException::class.java)
        val id = run("kalman", "v1")
        output(id, "filtered", """{"level": 1.0}""")
        assertThatThrownBy { output(id, "filtered", """{"level": 1.0}""") }.isInstanceOf(SQLException::class.java) // same date and kind
        assertThatThrownBy { output(id, "forecast", """{"level": 1.0}""") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { output(id, "smoothed", "1.0") }.isInstanceOf(SQLException::class.java)
        output(id, "smoothed", """{"level": 1.1}""")
    }

    private fun run(
        family: String,
        version: String,
        parameters: String = """{"a": 1}""",
        status: String = "challenger",
        supersedes: UUID? = null,
        rationale: String? = null,
    ): UUID =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    insert into mesta.model_run (tenant_id, model_family, model_version, methodology, frequency, data_vintage,
                                                 parameters, status, supersedes_id, rationale, actor, correlation_id)
                    values (?, ?, ?, 'test', 'quarterly', '2026-06-30', ?::jsonb, ?, ?, ?, 'integration-test', gen_random_uuid())
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    listOf(tenant, family, version, parameters, status, supersedes, rationale).forEachIndexed { i, v ->
                        statement.setObject(i + 1, v)
                    }
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
                }
        }

    private fun output(
        runId: UUID,
        kind: String,
        values: String,
    ) = execute(
        "insert into mesta.model_run_output (run_id, as_of_date, kind, values) values ('$runId', '2026-06-30', '$kind', '$values'::jsonb)",
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
