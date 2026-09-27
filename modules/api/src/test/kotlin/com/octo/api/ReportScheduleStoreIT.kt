package com.octo.api

import com.octo.workflow.TenantScope
import com.octo.workflow.report.JdbcReportScheduleStore
import com.octo.workflow.report.ReportSchedule
import com.octo.workflow.report.ReportType
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `JdbcReportScheduleStore` against the real V22 schema: upsert, the due-claim lease (a claimed row
 * is invisible until the lease lapses), and `markRun` advancing the cadence. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReportScheduleStoreIT {
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
    private val store by lazy { JdbcReportScheduleStore(dataSource) }

    private fun tenant(): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "insert into mesta.tenant (slug, display_name, source_system, correlation_id) " +
                            "values ('t-${UUID.randomUUID().toString().take(8)}', 'T', 'test', gen_random_uuid()) returning id",
                    ).use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
            }
        }

    private fun schedule(
        tenantId: UUID,
        nextRunAt: Instant = Instant.now().minus(1, ChronoUnit.MINUTES),
    ) = ReportSchedule(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        name = "LP quarterly",
        reportType = ReportType.PERFORMANCE,
        positionSourceType = "fund",
        positionSourceId = "fund-1",
        measures = listOf("tvpi", "irr"),
        parameters = "{}",
        cron = "0 0 8 * * MON",
        nextRunAt = nextRunAt,
        active = true,
        claimedUntil = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `an upserted schedule round-trips and updates in place`() {
        val tenantId = tenant()
        val saved = store.upsert(schedule(tenantId), TenantScope.Tenants(listOf(tenantId)))
        assertThat(store.load(saved.id, TenantScope.Tenants(listOf(tenantId)))!!.name).isEqualTo("LP quarterly")

        store.upsert(saved.copy(name = "LP monthly", active = false), TenantScope.Tenants(listOf(tenantId)))
        val reloaded = store.load(saved.id, TenantScope.Tenants(listOf(tenantId)))!!
        assertThat(reloaded.name).isEqualTo("LP monthly")
        assertThat(reloaded.active).isFalse()
        assertThat(store.list(tenantId, TenantScope.Tenants(listOf(tenantId)))).hasSize(1)
    }

    @Test
    fun `claimDue leases due rows only and markRun clears the lease`() {
        val tenantId = tenant()
        val now = Instant.now()
        val due = store.upsert(schedule(tenantId, now.minus(1, ChronoUnit.MINUTES)), TenantScope.All)
        store.upsert(schedule(tenantId, now.plus(1, ChronoUnit.HOURS)), TenantScope.All) // future: not due
        store.upsert(schedule(tenantId).copy(active = false), TenantScope.All) // inactive: not due

        val claimed = store.claimDue(now, Duration.ofMinutes(5))
        assertThat(claimed.map { it.id }).contains(due.id)
        // The claimed row is invisible until the lease lapses — a second poll never double-fires.
        assertThat(store.claimDue(now, Duration.ofMinutes(5)).map { it.id }).doesNotContain(due.id)

        val next = now.plus(7, ChronoUnit.DAYS)
        store.markRun(due.id, next)
        val reloaded = store.load(due.id, TenantScope.All)!!
        assertThat(reloaded.claimedUntil).isNull()
        assertThat(reloaded.nextRunAt).isEqualTo(next)
        assertThat(reloaded.updatedAt).isAfter(reloaded.createdAt)
    }

    @Test
    fun `a schedule renders its report request with calendar provenance`() {
        val tenantId = tenant()
        val schedule = schedule(tenantId)
        val correlation = UUID.randomUUID()
        val request = schedule.toRequest(correlation)
        assertThat(request.requestedBy).isEqualTo("schedule:${schedule.id}")
        assertThat(request.tenantId).isEqualTo(tenantId)
        assertThat(request.measures).containsExactly("tvpi", "irr")
        assertThat(request.correlationId).isEqualTo(correlation)
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
