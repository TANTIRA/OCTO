package com.octo.api

import com.octo.persistence.TenantScope
import com.octo.recon.matching.Break
import com.octo.recon.matching.BreakKind
import com.octo.recon.matching.persistence.JdbcReconciliationStore
import com.octo.workflow.Task
import com.octo.workflow.TaskKind
import com.octo.workflow.persistence.JdbcTaskStore
import com.octo.workflow.persistence.TaskProvenance
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
            .schemas("octo")
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
            "insert into octo.tenant (slug, display_name, source_system, correlation_id) " +
                "values ('t-${UUID.randomUUID().toString().take(8)}', 'T', 'test', gen_random_uuid()) returning id",
        )

    private fun event(
        system: String,
        externalId: String?,
        amount: String,
        supersedes: UUID? = null,
        tenantId: UUID? = null,
    ) = query(
        """
        insert into octo.ledger_event (external_id, flow_type, monetary_amount, currency_code, occurred_at, supersedes_id, rationale,
                                        tenant_id, source_system, actor, ingestion_run_id, correlation_id)
        values (${externalId?.let {
            "'$it'"
        } ?: "null"}, 'contribution', $amount, 'USD', '2026-06-30T10:00:00Z', ${supersedes?.let { "'$it'" } ?: "null"},
                ${supersedes?.let { "'corrected'" } ?: "null"}, ${tenantId?.let {
            "'$it'"
        } ?: "null"}, '$system', 'it', gen_random_uuid(), gen_random_uuid())
        returning id
        """.trimIndent(),
    )

    private fun task() =
        query(
            "insert into octo.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id) " +
                "values ('evidence-request', 'reconciliation-break', 'x', 'runner', 'test', gen_random_uuid()) returning id",
        )

    @Test
    fun `the ledger side holds only current events of the source system, dated in the zone`() {
        val system = "admin-${UUID.randomUUID().toString().take(8)}"
        val tenantId = tenant()
        val scope = TenantScope.Tenants(listOf(tenantId))
        val original = event(system, "t-1", "-100", tenantId = tenantId)
        // V1: the key stays on the root; the correction carries none
        val corrected = event(system, null, "-101", supersedes = original, tenantId = tenantId)
        val unattributed = event(system, null, "5", tenantId = tenantId)
        event("other-system", "t-1", "-100", tenantId = tenantId)

        val records = store.iborRecords(tenantId, system, ZoneOffset.UTC, scope).associateBy { it.id }
        assertThat(records.keys).containsExactlyInAnyOrder(corrected, unattributed)
        assertThat(records.getValue(corrected).externalId).isEqualTo("t-1")
        assertThat(records.getValue(corrected).amount).isEqualByComparingTo("-101")
        assertThat(records.getValue(corrected).date).isEqualTo(LocalDate.parse("2026-06-30"))
        assertThat(records.getValue(unattributed).externalId).isNull()
    }

    @Test
    fun `the same external id may belong to different tenants`() {
        val system = "admin-${UUID.randomUUID().toString().take(8)}"
        val first = tenant()
        val second = tenant()
        val original = event(system, "shared", "-100", tenantId = first)
        val other = event(system, "shared", "-200", tenantId = second)
        val corrected = event(system, null, "-101", supersedes = original, tenantId = first)
        assertThat(store.iborRecords(first, system, ZoneOffset.UTC, TenantScope.Tenants(listOf(first))).map { it.id })
            .containsExactly(corrected)
        assertThat(store.iborRecords(second, system, ZoneOffset.UTC, TenantScope.Tenants(listOf(second))).map { it.id })
            .containsExactly(other)
        assertThat(store.iborRecords(first, system, ZoneOffset.UTC, TenantScope.Tenants(listOf(second)))).isEmpty()
        event(system, null, "-1")
        assertThat(store.iborRecords(first, system, ZoneOffset.UTC, TenantScope.All).map { it.id })
            .containsExactly(corrected)
        assertThatThrownBy { event(system, null, "-201", supersedes = other, tenantId = first) }
            .isInstanceOf(SQLException::class.java)
        assertThatThrownBy { event(system, "unowned", "-1") }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a break records with its task once per key, and the task is found by later runs`() {
        val tenantId = tenant()
        val eventId = event("admin-x", "t-2", "-50", tenantId = tenantId)
        val brk = Break(BreakKind.AMOUNT_MISMATCH, "admin-x", "t-2", eventId, mapOf("source" to "-49", "ibor" to "-50"))
        assertThat(store.existingTask(tenantId, brk, TenantScope.All)).isNull()

        val taskId = task()
        store.record(tenantId, UUID.randomUUID(), brk, taskId, UUID.randomUUID(), TenantScope.All)
        assertThat(store.existingTask(tenantId, brk, TenantScope.All)).isEqualTo(taskId)
        assertThatThrownBy {
            store.record(
                tenantId,
                UUID.randomUUID(),
                brk,
                task(),
                UUID.randomUUID(),
                TenantScope.All,
            )
        }.isInstanceOf(SQLException::class.java)
        store.record(tenantId, UUID.randomUUID(), brk, null, UUID.randomUUID(), TenantScope.All) // tomorrow's repeat, no new task
        val otherTenant = tenant()
        assertThat(store.existingTask(otherTenant, brk, TenantScope.All)).isNull() // another tenant does not see it
        assertThatThrownBy {
            store.record(otherTenant, UUID.randomUUID(), brk, null, UUID.randomUUID(), TenantScope.All)
        }.isInstanceOf(SQLException::class.java)

        val missing = Break(BreakKind.MISSING_IN_IBOR, "admin-x", "t-3", null, emptyMap())
        store.record(tenantId, UUID.randomUUID(), missing, task(), UUID.randomUUID(), TenantScope.All)
        assertThat(store.existingTask(tenantId, missing, TenantScope.All)).isNotNull()
        assertThatThrownBy {
            store.record(tenantId, UUID.randomUUID(), missing.copy(ledgerEventId = eventId), null, UUID.randomUUID(), TenantScope.All)
        }.isInstanceOf(SQLException::class.java) // missing-in-ibor may not carry an event
    }

    @Test
    fun `a task opened on the record's transaction commits with its break and rolls back when the break is refused`() {
        val tenantId = tenant()
        val eventId = event("admin-y", "t-4", "-70", tenantId = tenantId)
        val brk = Break(BreakKind.AMOUNT_MISMATCH, "admin-y", "t-4", eventId, mapOf("source" to "-69", "ibor" to "-70"))
        val tasks = JdbcTaskStore(dataSource)

        fun runnerTask() =
            Task(UUID.randomUUID(), TaskKind.EVIDENCE_REQUEST, "reconciliation-break", "admin-y/t-4", "runner", Instant.now())

        val kept = runnerTask()
        store.record(tenantId, UUID.randomUUID(), brk, kept.id, UUID.randomUUID(), TenantScope.All) { connection ->
            tasks.create(connection, kept, TaskProvenance("it", UUID.randomUUID()))
        }
        assertThat(store.existingTask(tenantId, brk, TenantScope.All)).isEqualTo(kept.id)
        assertThat(tasks.load(kept.id)).isNotNull()

        // The losing runner of a race: V15 refuses its break, and its task must not outlive the refusal (#341).
        val lost = runnerTask()
        assertThatThrownBy {
            store.record(tenantId, UUID.randomUUID(), brk, lost.id, UUID.randomUUID(), TenantScope.All) { connection ->
                tasks.create(connection, lost, TaskProvenance("it", UUID.randomUUID()))
            }
        }.isInstanceOf(SQLException::class.java)
        assertThat(tasks.load(lost.id)).isNull()
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
