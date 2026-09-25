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
 * Runs the real Flyway migrations and exercises the V14 compliance invariants (#106): immutable rule versions,
 * a json definition with a check, append-only evaluations, a task only on a breach, and one breach per
 * rule, subject and date. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class ComplianceMigrationIT {
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
    fun `migration creates both tables, their append-only triggers and the breach index`() {
        for (table in listOf("compliance_rule", "compliance_evaluation")) {
            assertThat(count("select count(*) from pg_tables where schemaname = 'mesta' and tablename = '$table'")).isEqualTo(1)
            assertThat(count("select count(*) from pg_trigger where tgname = '${table}_append_only'")).isEqualTo(1)
        }
        assertThat(count("select count(*) from pg_indexes where indexname = 'compliance_breach_once'")).isEqualTo(1)
    }

    @Test
    fun `a rule version is immutable and its definition names a check`() {
        val id = rule("conc-25", 1)
        assertThatThrownBy { rule("conc-25", 1) }.isInstanceOf(SQLException::class.java)
        rule("conc-25", 2)
        assertThatThrownBy { rule("Conc 25", 3) }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { rule("conc-25", 0) }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { rule("no-check", 1, definition = """{"maxFraction": "0.25"}""") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { execute("update mesta.compliance_rule set active = false where id = '$id'") }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("append-only")
    }

    @Test
    fun `a breach is recorded once per rule, subject and date, and only a breach carries a task`() {
        val task = task()
        evaluation("conc-25", "fund-1", "breach", task)
        assertThatThrownBy { evaluation("conc-25", "fund-1", "breach", task()) }.isInstanceOf(SQLException::class.java)
        evaluation("conc-25", "fund-1", "pass") // a later pass on the same day is a new fact
        evaluation("conc-25", "fund-2", "breach", task())
        assertThatThrownBy { evaluation("conc-25", "fund-3", "pass", task()) }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { evaluation("conc-25", "fund-3", "warning") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            execute(
                "delete from mesta.compliance_evaluation where subject = 'fund-1'",
            )
        }.isInstanceOf(SQLException::class.java)
    }

    private fun rule(
        ruleId: String,
        version: Int,
        definition: String = """{"check": "concentration-limit", "maxFraction": "0.25"}""",
    ): UUID =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    insert into mesta.compliance_rule (tenant_id, rule_id, version, name, definition, actor, correlation_id)
                    values (?, ?, ?, 'Concentration', ?::jsonb, 'integration-test', gen_random_uuid())
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    listOf(tenant, ruleId, version, definition).forEachIndexed { i, v -> statement.setObject(i + 1, v) }
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
                }
        }

    private fun evaluation(
        ruleId: String,
        subject: String,
        result: String,
        taskId: UUID? = null,
    ) = dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                """
                insert into mesta.compliance_evaluation (tenant_id, rule_id, rule_version, subject, as_of_date, result, explanation, task_id, correlation_id)
                values (?, ?, 1, ?, '2026-06-30', ?, 'largest holding is 0.5 of gross', ?, gen_random_uuid())
                """.trimIndent(),
            ).use { statement ->
                listOf(tenant, ruleId, subject, result, taskId).forEachIndexed { i, v -> statement.setObject(i + 1, v) }
                statement.executeUpdate()
            }
    }

    private fun task(): UUID =
        query(
            """
            insert into mesta.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id)
            values ('review', 'compliance-breach', 'x', 'compliance-runner', 'test', gen_random_uuid()) returning id
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
