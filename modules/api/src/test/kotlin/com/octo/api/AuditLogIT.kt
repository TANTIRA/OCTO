package com.mesta.asset.api

import com.mesta.asset.workflow.audit.AuditEntry
import com.mesta.asset.workflow.audit.ChainBreak
import com.mesta.asset.workflow.audit.JdbcAuditLog
import com.mesta.asset.workflow.audit.verifyAuditChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * V6's audit log end to end, with the deployed role split: Flyway runs as a non-superuser migration role that
 * owns the table and the SECURITY DEFINER trigger, and the api's runtime role may only insert. Each test leaves
 * the chain intact, so they can run in any order. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class AuditLogIT {
    @Test
    fun `the runtime role builds a chain that verifies`() {
        val before = auditor.readAll().size
        runtimeLog.append(entry("alice"))
        runtimeLog.append(entry("Rāde")) // ā is two UTF-8 bytes: SQL and Kotlin must count the same

        val records = auditor.readAll()
        assertThat(records.map { it.seq }).isEqualTo((1L..records.size).toList())
        assertThat(records).hasSize(before + 2)
        assertThat(verifyAuditChain(records)).isNull()
    }

    @Test
    fun `the runtime role can append but never read the log`() {
        runtimeLog.append(entry("alice"))
        assertSqlState(INSUFFICIENT_PRIVILEGE) { JdbcAuditLog(runtime).readAll() }
        assertSqlState(INSUFFICIENT_PRIVILEGE) { execute(runtime, "delete from mesta.audit_event") }
    }

    @Test
    fun `the database assigns seq, recorded_at and the hashes whatever the caller sends`() {
        execute(
            owner,
            """
            insert into mesta.audit_event (seq, occurred_at, recorded_at, actor, action, subject_type, subject_id,
                                           correlation_id, prev_hash, hash)
            values (999, now(), '2000-01-01', 'forger', 'x', 'y', 'z', gen_random_uuid(), '\x00', '\x00')
            """.trimIndent(),
        )
        val records = auditor.readAll()
        assertThat(records.last().seq).isEqualTo(records.size.toLong())
        assertThat(records.last().recordedAt).isAfter(Instant.parse("2020-01-01T00:00:00Z"))
        assertThat(verifyAuditChain(records)).isNull()
    }

    @Test
    fun `update, delete and truncate are refused even to the owner, and a superuser's edit is detected`() {
        runtimeLog.append(entry("original"))
        val seq = auditor.readAll().last().seq
        for (sql in listOf(
            "update mesta.audit_event set actor = 'x' where seq = $seq",
            "delete from mesta.audit_event",
            "truncate mesta.audit_event",
        )) {
            assertSqlState(RESTRICT_VIOLATION) { execute(owner, sql) }
        }

        superuserEdit("update mesta.audit_event set actor = 'mallory' where seq = $seq")
        assertThat(verifyAuditChain(auditor.readAll())).isEqualTo(ChainBreak(seq, "hash does not match the row's contents"))

        superuserEdit("update mesta.audit_event set actor = 'original' where seq = $seq")
        assertThat(verifyAuditChain(auditor.readAll())).isNull()
    }

    private fun entry(actor: String) =
        AuditEntry(Instant.now(), actor, "task.approved", "workflow-task", "task-1", UUID.randomUUID(), """{"task": "task-1"}""")

    /** What only a superuser can do: switch the append-only trigger off around an edit. */
    private fun superuserEdit(sql: String) {
        execute(superuser, "alter table mesta.audit_event disable trigger audit_event_append_only")
        execute(superuser, sql)
        execute(superuser, "alter table mesta.audit_event enable trigger audit_event_append_only")
    }

    private fun assertSqlState(
        state: String,
        action: () -> Unit,
    ) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo(state) }
    }

    private companion object {
        const val MIGRATOR = "mesta_migrator"
        const val RUNTIME = "mesta_app"

        /** SQLSTATE for a missing privilege, and the one `mesta.reject_mutation()` raises. */
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val RESTRICT_VIOLATION = "23001"

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("admin")
                .withPassword("admin")

        val superuser: DataSource by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }

        /** Roles are cluster-wide, so they are created and the schema migrated once per class. */
        private val migrated by lazy<Unit> {
            execute(superuser, "create role $MIGRATOR login password '$MIGRATOR'")
            execute(superuser, "grant create on database ${postgres.databaseName} to $MIGRATOR")
            execute(superuser, "create role $RUNTIME login password '$RUNTIME'")
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, MIGRATOR, MIGRATOR)
                .locations("classpath:db/migration")
                .schemas("mesta")
                .placeholders(mapOf("runtime_role" to RUNTIME))
                .load()
                .migrate()
        }

        val owner: DataSource by lazy { migrated.let { DriverManagerDataSource(postgres.jdbcUrl, MIGRATOR, MIGRATOR) } }
        val runtime: DataSource by lazy { migrated.let { DriverManagerDataSource(postgres.jdbcUrl, RUNTIME, RUNTIME) } }
        val runtimeLog: JdbcAuditLog by lazy { JdbcAuditLog(runtime) }

        /** The owner stands in for an auditor role, which does not exist yet. */
        val auditor: JdbcAuditLog by lazy { JdbcAuditLog(owner) }

        fun execute(
            dataSource: DataSource,
            sql: String,
        ) {
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
        }
    }
}
