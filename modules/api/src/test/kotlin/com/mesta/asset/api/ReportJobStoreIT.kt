package com.mesta.asset.api

import com.mesta.asset.workflow.report.JdbcReportJobStore
import com.mesta.asset.workflow.report.JobStatus
import com.mesta.asset.workflow.report.ReportRequest
import com.mesta.asset.workflow.report.ReportType
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

/** `JdbcReportJobStore` against the real V13 schema: submit, claim in order, complete, fail, and the trigger's refusals. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class ReportJobStoreIT {
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
    private val store by lazy { JdbcReportJobStore(dataSource) }

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
        val first = store.submit(request(tenantId))
        val second = store.submit(request(tenantId).copy(measures = listOf("dpi")))
        assertThat(first.status).isEqualTo(JobStatus.NEW)
        assertThat(first.request.measures).containsExactly("tvpi", "irr")

        val claimed = generateSequence { store.claimNext() }.toList()
        assertThat(claimed.map { it.id }).containsSubsequence(first.id, second.id)
        assertThat(claimed).allMatch { it.status == JobStatus.EXECUTING }
        assertThat(store.claimNext()).isNull()

        val done = store.complete(first.id, """{"tvpi": 1.5}""", "a".repeat(64))
        assertThat(done.status).isEqualTo(JobStatus.DONE)
        assertThat(done.result).contains("1.5")
        assertThat(done.artifactSha256).isEqualTo("a".repeat(64))
        assertThat(done.updatedAt).isAfterOrEqualTo(done.createdAt)
        val failed = store.fail(second.id, "engine refused the series")
        assertThat(failed.status).isEqualTo(JobStatus.ERROR)
        assertThat(store.load(second.id)!!.error).isEqualTo("engine refused the series")
        assertThat(store.load(UUID.randomUUID())).isNull()
    }

    @Test
    fun `the trigger refuses a transition out of order and the store surfaces it`() {
        val job = store.submit(request(tenant()))
        assertThatThrownBy { store.complete(job.id, "{}") }.isInstanceOf(SQLException::class.java) // still new
        assertThatThrownBy { store.fail(UUID.randomUUID(), "x") }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { request(job.request.tenantId).copy(measures = listOf(" ")) }.isInstanceOf(IllegalArgumentException::class.java)
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
