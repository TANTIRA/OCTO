package com.mesta.asset.api

import com.mesta.asset.recon.matching.Break
import com.mesta.asset.recon.matching.BreakKind
import com.mesta.asset.recon.matching.persistence.JdbcReconciliationStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** `JdbcReconciliationStore` against V1 and V15: the ledger side of a run and break recording with the one-task rule. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationStoreIT {
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
    private val store by lazy { JdbcReconciliationStore(dataSource) }

    private fun query(sql: String): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getObject(1, UUID::class.java)
                }
            }
        }

    private fun tenant() =
        query(
            "insert into mesta.tenant (slug, display_name, source_system, correlation_id) " +
                "values ('t-${UUID.randomUUID().toString().take(8)}', 'T', 'test', gen_random_uuid()) returning id",
        )

    private fun event(
        system: String,
        externalId: String?,
        amount: String,
        supersedes: UUID? = null,
    ) = query(
        """
        insert into mesta.ledger_event (external_id, flow_type, monetary_amount, currency_code, occurred_at, supersedes_id, rationale,
                                        source_system, actor, ingestion_run_id, correlation_id)
        values (${externalId?.let {
            "'$it'"
        } ?: "null"}, 'contribution', $amount, 'USD', '2026-06-30T10:00:00Z', ${supersedes?.let { "'$it'" } ?: "null"},
                ${supersedes?.let { "'corrected'" } ?: "null"}, '$system', 'it', gen_random_uuid(), gen_random_uuid())
        returning id
        """.trimIndent(),
    )

    private fun task() =
        query(
            "insert into mesta.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id) " +
                "values ('evidence-request', 'reconciliation-break', 'x', 'runner', 'test', gen_random_uuid()) returning id",
        )

    @Test
    fun `the ledger side holds only current events of the source system, dated in the zone`() {
        val system = "admin-${UUID.randomUUID().toString().take(8)}"
        val original = event(system, "t-1", "-100")
        val corrected = event(system, null, "-101", supersedes = original) // V1: the key stays on the root; the correction carries none
        val unattributed = event(system, null, "5")
        event("other-system", "t-1", "-100")

        val records = store.iborRecords(system, ZoneOffset.UTC).associateBy { it.id }
        assertThat(records.keys).containsExactlyInAnyOrder(corrected, unattributed)
        assertThat(records.getValue(corrected).externalId).isEqualTo("t-1")
        assertThat(records.getValue(corrected).amount).isEqualByComparingTo("-101")
        assertThat(records.getValue(corrected).date).isEqualTo(LocalDate.parse("2026-06-30"))
        assertThat(records.getValue(unattributed).externalId).isNull()
    }

    @Test
    fun `a break records with its task once per key, and the task is found by later runs`() {
        val tenantId = tenant()
        val eventId = event("admin-x", "t-2", "-50")
        val brk = Break(BreakKind.AMOUNT_MISMATCH, "admin-x", "t-2", eventId, mapOf("source" to "-49", "ibor" to "-50"))
        assertThat(store.existingTask(tenantId, brk)).isNull()

        val taskId = task()
        store.record(tenantId, UUID.randomUUID(), brk, taskId, UUID.randomUUID())
        assertThat(store.existingTask(tenantId, brk)).isEqualTo(taskId)
        assertThatThrownBy {
            store.record(
                tenantId,
                UUID.randomUUID(),
                brk,
                task(),
                UUID.randomUUID(),
            )
        }.isInstanceOf(SQLException::class.java)
        store.record(tenantId, UUID.randomUUID(), brk, null, UUID.randomUUID()) // tomorrow's repeat, no new task
        assertThat(store.existingTask(tenant(), brk)).isNull() // another tenant does not see it

        val missing = Break(BreakKind.MISSING_IN_IBOR, "admin-x", "t-3", null, emptyMap())
        store.record(tenantId, UUID.randomUUID(), missing, task(), UUID.randomUUID())
        assertThat(store.existingTask(tenantId, missing)).isNotNull()
        assertThatThrownBy { store.record(tenantId, UUID.randomUUID(), missing.copy(ledgerEventId = eventId), null, UUID.randomUUID()) }
            .isInstanceOf(SQLException::class.java) // missing-in-ibor may not carry an event
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
