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
 * V5 on its own: the rules the database enforces for a caller that writes workflow rows directly, without the
 * Kotlin state machine. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class WorkflowMigrationIT {
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
    fun `a decision by someone other than the requester is accepted`() {
        val task = newTask()
        rawEvent(task, "assigned", actor = "alice", assignee = "bob")
        rawEvent(task, "approved", actor = "bob")

        assertThat(count("select count(*) from mesta.workflow_task_event where task_id = '$task'")).isEqualTo(2)
    }

    @Test
    fun `segregation of duties is enforced on approvals`() {
        val task = newTask()

        assertRefused(CHECK_VIOLATION) { rawEvent(task, "approved", actor = "alice") }
        assertRefused(CHECK_VIOLATION) { rawEvent(task, "assigned", actor = "carol", assignee = "alice") }
        rawEvent(task, "rework-requested", actor = "bob", rationale = "method not stated")
        assertRefused(CHECK_VIOLATION) { rawEvent(task, "resubmitted", actor = "bob") }
    }

    @Test
    fun `a decided task stays decided`() {
        val task = newTask()
        rawEvent(task, "approved", actor = "bob")

        assertRefused(UNIQUE_VIOLATION) { rawEvent(task, "rejected", actor = "carol", rationale = "second opinion") }
    }

    @Test
    fun `a rejection needs a rationale and an assignment needs an assignee`() {
        val task = newTask()

        assertRefused(CHECK_VIOLATION) { rawEvent(task, "rejected", actor = "bob") }
        assertRefused(CHECK_VIOLATION) { rawEvent(task, "assigned", actor = "alice") }
    }

    @Test
    fun `tasks and events are append-only`() {
        val task = newTask(kind = "review")
        rawEvent(task, "completed", actor = "alice")

        for (table in listOf("workflow_task", "workflow_task_event")) {
            assertRefused(RESTRICT_VIOLATION) { execute("delete from mesta.$table") }
        }
    }

    private fun newTask(kind: String = "approval"): UUID {
        val id = UUID.randomUUID()
        execute(
            "insert into mesta.workflow_task (id, kind, subject_type, subject_id, requested_by, source_system, correlation_id) " +
                "values ('$id', '$kind', 'valuation-event', 've-1', 'alice', 'test', gen_random_uuid())",
        )
        return id
    }

    private fun rawEvent(
        task: UUID,
        type: String,
        actor: String,
        assignee: String? = null,
        rationale: String? = null,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "insert into mesta.workflow_task_event " +
                        "(task_id, event_type, actor, assignee, rationale, occurred_at, correlation_id) " +
                        "values (?, ?, ?, ?, ?, now(), gen_random_uuid())",
                ).use { statement ->
                    statement.setObject(1, task)
                    statement.setString(2, type)
                    statement.setString(3, actor)
                    statement.setString(4, assignee)
                    statement.setString(5, rationale)
                    statement.executeUpdate()
                }
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { it.createStatement().use { statement -> statement.execute(sql) } }
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
        const val UNIQUE_VIOLATION = "23505"
        const val RESTRICT_VIOLATION = "23001"

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")
    }
}
