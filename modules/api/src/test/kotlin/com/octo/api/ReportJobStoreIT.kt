package com.octo.api

import com.octo.persistence.TenantScope
import com.octo.workflow.report.JdbcReportJobStore
import com.octo.workflow.report.JobStatus
import com.octo.workflow.report.ReportRequest
import com.octo.workflow.report.ReportType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

/** `JdbcReportJobStore` against the real V13 schema: submit, claim in order, complete, fail, and the trigger's refusals. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class ReportJobStoreIT {
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
    private val store by lazy { JdbcReportJobStore(dataSource) }

    private fun tenant(): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "insert into octo.tenant (slug, display_name, source_system, correlation_id) " +
                            "values ('t-${UUID.randomUUID().toString().take(8)}', 'T', 'test', gen_random_uuid()) returning id",
                    ).use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
            }
        }

    private fun request(tenantId: UUID) =
        ReportRequest(
            tenantId,
            ReportType.PERFORMANCE,
            "inline-series",
            "fund-1",
            listOf("tvpi", "irr"),
            """{"nav": "120"}""",
            "analyst-1",
            UUID.randomUUID(),
        )

    @Test
    fun `jobs are claimed oldest first, once, and end done or error`() {
        // Each test gets its own tenant, but the queue is global: drain whatever other tests left before asserting order.
        val tenantId = tenant()
        val first = store.submit(request(tenantId), TenantScope.All)
        val second = store.submit(request(tenantId).copy(measures = listOf("dpi")), TenantScope.All)
        assertThat(first.status).isEqualTo(JobStatus.NEW)
        assertThat(first.request.measures).containsExactly("tvpi", "irr")

        val claimed = generateSequence { store.claimNext() }.toList()
        assertThat(claimed.map { it.id }).containsSubsequence(first.id, second.id)
        assertThat(claimed).allMatch { it.status == JobStatus.EXECUTING }
        assertThat(store.claimNext()).isNull()

        val done = store.complete(first.id, claimed.first { it.id == first.id }.claimToken!!, """{"tvpi": 1.5}""", "a".repeat(64))
        assertThat(done.status).isEqualTo(JobStatus.DONE)
        assertThat(done.result).contains("1.5")
        assertThat(done.artifactSha256).isEqualTo("a".repeat(64))
        assertThat(done.updatedAt).isAfterOrEqualTo(done.createdAt)
        val failed = store.fail(second.id, claimed.first { it.id == second.id }.claimToken!!, "engine refused the series")
        assertThat(failed.status).isEqualTo(JobStatus.ERROR)
        assertThat(store.load(second.id, TenantScope.All)!!.error).isEqualTo("engine refused the series")
        assertThat(store.load(UUID.randomUUID(), TenantScope.All)).isNull()
    }

    @Test
    fun `an expired claim is reclaimed and only the new claimant can finish`() {
        generateSequence { store.claimNext() }.toList()
        val job = store.submit(request(tenant()), TenantScope.All)
        val initial = JdbcReportJobStore(dataSource, Duration.ofMillis(100)).claimNext()!!
        assertThat(initial.id).isEqualTo(job.id)
        assertThat(store.claimNext()).isNull()
        Thread.sleep(250)
        val reclaimed = store.claimNext()!!
        assertThat(reclaimed.id).isEqualTo(job.id)
        assertThat(reclaimed.claimToken).isNotEqualTo(initial.claimToken)
        assertThat(store.renew(job.id, initial.claimToken!!)).isFalse()
        assertThatThrownBy { store.complete(job.id, initial.claimToken!!, "{}") }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(store.renew(job.id, reclaimed.claimToken!!)).isTrue()
        val done = store.complete(job.id, reclaimed.claimToken!!, "{}")
        assertThat(done.status).isEqualTo(JobStatus.DONE)
        assertThat(done.claimToken).isNull()
        assertThat(done.claimedUntil).isNull()
        assertThat(store.claimNext()).isNull()
    }

    @Test
    fun `pendingCount counts only the tenant's unfinished jobs`() {
        val tenantId = tenant()
        val scope = TenantScope.Tenants(listOf(tenantId))
        assertThat(store.pendingCount(tenantId, scope)).isZero()

        val job = store.submit(request(tenantId), TenantScope.All)
        store.submit(request(tenant()), TenantScope.All) // another tenant does not count
        assertThat(store.pendingCount(tenantId, scope)).isEqualTo(1)
        assertThat(store.pendingCount(tenantId, TenantScope.Tenants(listOf(UUID.randomUUID())))).isZero()

        // claimed counts as pending until it finishes
        val claimed = generateSequence { store.claimNext() }.first { it.id == job.id }
        assertThat(store.pendingCount(tenantId, scope)).isEqualTo(1)
        store.complete(claimed.id, claimed.claimToken!!, "{}")
        assertThat(store.pendingCount(tenantId, scope)).isZero()
    }

    @Test
    fun `a scope that does not admit the job's tenant hides it and refuses the write`() {
        val tenantId = tenant()
        val elsewhere = TenantScope.Tenants(listOf(UUID.randomUUID()))
        val job = store.submit(request(tenantId), TenantScope.All)
        assertThat(store.load(job.id, elsewhere)).isNull()
        assertThatThrownBy { store.submit(request(tenantId), elsewhere) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the trigger refuses a transition out of order and the store surfaces it`() {
        val job = store.submit(request(tenant()), TenantScope.All)
        assertThatThrownBy { store.complete(job.id, UUID.randomUUID(), "{}") }
            .isInstanceOf(NoSuchElementException::class.java) // still new
        assertThatThrownBy { store.fail(UUID.randomUUID(), UUID.randomUUID(), "x") }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { request(job.request.tenantId).copy(measures = listOf(" ")) }.isInstanceOf(IllegalArgumentException::class.java)
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
