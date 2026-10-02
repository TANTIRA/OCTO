package com.octo.api.graph

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.access.Tenant
import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.access.persistence.JdbcAccessStore
import com.octo.api.asset.Asset
import com.octo.api.asset.AssetProvenance
import com.octo.api.asset.AssetType
import com.octo.api.asset.persistence.JdbcAssetStore
import com.octo.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/**
 * `octo.graph_outbox` (V45) and `JdbcGraphOutboxStore` against real Postgres: the asset row and its graph intent
 * commit together, claims respect per-node order and leases, and a row's retry budget ends in `failed`, never in a
 * skip. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class GraphOutboxStoreIT {
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
    private val assets by lazy { JdbcAssetStore(dataSource) }
    private val provenance = AssetProvenance("integration-test", "quant-1", UUID.randomUUID())

    private fun tenant(): UUID =
        Tenant(UUID.randomUUID(), "t-${UUID.randomUUID().toString().take(8)}", "Tenant")
            .also { JdbcAccessStore(dataSource).createTenant(it, AccessProvenance("integration-test", UUID.randomUUID())) }
            .id

    private fun store(
        tenantId: UUID,
        name: String,
        type: AssetType = AssetType.FUND,
        supersedes: UUID? = null,
    ): Asset =
        Asset(
            UUID.randomUUID(),
            tenantId,
            type,
            "private-equity",
            name,
            supersedesId = supersedes,
            rationale = supersedes?.let { "renamed" },
        ).also { assets.create(it, emptyList(), provenance, TenantScope.Tenants(listOf(tenantId))) }

    private fun outbox(
        maxAttempts: Int = 8,
        maxBackoff: Duration = Duration.ofMinutes(15),
    ) = JdbcGraphOutboxStore(dataSource, lease = Duration.ofMinutes(2), maxAttempts = maxAttempts, maxBackoff = maxBackoff)

    private fun rows(aggregateId: UUID): List<Pair<String, Int>> =
        dataSource.connection.use { connection ->
            val sql = "select status, attempts from octo.graph_outbox where aggregate_id = ? order by seq"
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, aggregateId)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) to rows.getInt(2) else null }.toList()
                }
            }
        }

    @BeforeEach
    fun emptyOutbox() {
        dataSource.connection.use { it.createStatement().execute("delete from octo.graph_outbox") }
    }

    @Test
    fun `an asset enqueues its lineage upsert in the same transaction, and a refused write enqueues nothing`() {
        val tenantId = tenant()
        val original = store(tenantId, "PT Acme", AssetType.OPERATING_COMPANY)
        val corrected = store(tenantId, "PT Acme Logistik", AssetType.OPERATING_COMPANY, supersedes = original.id)

        val claimed = outbox().claim(10).single() // the correction waits behind the original's pending upsert
        assertThat(claimed.aggregateId).isEqualTo(original.id)
        assertThat(claimed.tenantId).isEqualTo(tenantId)
        val payload = ObjectMapper().readTree(claimed.payload)
        assertThat(payload["kind"].asText()).isEqualTo("operating-company")
        assertThat(payload["properties"]["legalName"].asText()).isEqualTo("PT Acme")
        assertThat(rows(original.id)).hasSize(2) // both target the lineage root
        assertThat(rows(corrected.id)).isEmpty()

        val orphanId = UUID.randomUUID()
        assertThatThrownBy { store(tenantId, "Ghost Fund", supersedes = orphanId) }.isInstanceOf(SQLException::class.java)
        assertThat(rows(orphanId)).isEmpty()
    }

    @Test
    fun `claims follow per-node order, honour the lease, and release spends no attempt`() {
        val tenantId = tenant()
        val first = store(tenantId, "Fund I")
        store(tenantId, "Fund I LP", supersedes = first.id)
        val other = store(tenantId, "Fund II")
        val store = outbox()

        val claimed = store.claim(10)
        assertThat(claimed.map { it.aggregateId }).containsExactly(first.id, other.id)
        assertThat(store.claim(10)).isEmpty() // leased rows are not claimed twice

        store.applied(claimed[0])
        store.release(claimed[1])
        val next = store.claim(10)
        assertThat(next.map { it.aggregateId }).containsExactlyInAnyOrder(first.id, other.id) // the correction, then Fund II again
        assertThat(next.single { it.aggregateId == other.id }.attempts).isZero()
        assertThat(rows(first.id).map { it.first }).containsExactly("applied", "pending")
    }

    @Test
    fun `a failure backs off, and the last attempt marks the row failed and counts it`() {
        val tenantId = tenant()
        val fund = store(tenantId, "Fund III")

        val backingOff = outbox()
        backingOff.failed(backingOff.claim(10).single(), "Neo.ClientError.Schema.ConstraintValidationFailed")
        assertThat(rows(fund.id)).containsExactly("pending" to 1)
        assertThat(backingOff.claim(10)).isEmpty() // next_attempt_at is in the future

        dataSource.connection.use { it.createStatement().execute("update octo.graph_outbox set next_attempt_at = now()") }
        val lastTry = outbox(maxAttempts = 2, maxBackoff = Duration.ZERO)
        lastTry.failed(lastTry.claim(10).single(), "Neo.ClientError.Schema.ConstraintValidationFailed")
        assertThat(rows(fund.id)).containsExactly("failed" to 2)
        assertThat(lastTry.claim(10)).isEmpty()

        val stats = lastTry.stats()
        assertThat(stats.failed).isEqualTo(1)
        assertThat(stats.pending).isZero()
    }

    @Test
    fun `stats report pending lag, and prune drops only applied rows past retention`() {
        val tenantId = tenant()
        val applied = store(tenantId, "Fund IV")
        val pending = store(tenantId, "Fund V")
        val store = outbox()
        store.applied(store.claim(10).single { it.aggregateId == applied.id })

        assertThat(store.stats().pending).isEqualTo(1) // Fund V: claimed but not applied is still pending
        assertThat(store.stats().oldestPendingSeconds).isGreaterThanOrEqualTo(0.0)
        assertThat(store.prune(Duration.ofDays(30))).isZero()
        assertThat(store.prune(Duration.ZERO)).isEqualTo(1)
        assertThat(rows(applied.id)).isEmpty()
        assertThat(rows(pending.id)).hasSize(1)
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
