package com.mesta.asset.api

import com.mesta.asset.ingestion.persistence.JdbcTimeSeriesStore
import com.mesta.asset.ingestion.persistence.Observation
import com.mesta.asset.ingestion.persistence.ObservationProvenance
import com.mesta.asset.ingestion.persistence.TimeSeriesQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/** `JdbcTimeSeriesStore` against the real V12 schema: the bi-temporal query semantics and atomic writes. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class TimeSeriesStoreIT {
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
    private val store by lazy { JdbcTimeSeriesStore(dataSource) }
    private val provenance = ObservationProvenance("integration-test", "loader-1", UUID.randomUUID(), UUID.randomUUID())
    private val q2 = LocalDate.parse("2026-06-30")
    private val q1 = LocalDate.parse("2026-03-31")

    private fun dataset(): Pair<UUID, UUID> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        with t as (insert into mesta.tenant (slug, display_name, source_system, correlation_id)
                                   values ('t-${UUID.randomUUID().toString().take(8)}', 'T', 'test', gen_random_uuid()) returning id)
                        insert into mesta.dataset (tenant_id, name, description, unit, source_system, actor, correlation_id)
                        select id, 'nav.quarterly', 'NAV', 'currency', 'test', 'it', gen_random_uuid() from t
                        returning id, tenant_id
                        """.trimIndent(),
                    ).use { rows ->
                        rows.next()
                        rows.getObject("id", UUID::class.java) to rows.getObject("tenant_id", UUID::class.java)
                    }
            }
        }

    private fun nav(
        datasetId: UUID,
        date: LocalDate,
        value: String,
        field: String = "nav",
    ) = Observation(datasetId, "fund-1", field, date, BigDecimal(value))

    @Test
    fun `asOfTime gives the value in force then, since gives what changed, fields select`() {
        val (ds, tenant) = dataset()
        assertThat(store.datasetTenant(ds)).isEqualTo(tenant)
        val loaded =
            store.write(listOf(nav(ds, q2, "100"), nav(ds, q1, "90"), nav(ds, q2, "0.12", field = "irr")), provenance).maxOf {
                it.recordedAt!!
            }
        val restated = store.write(listOf(nav(ds, q2, "101").copy(supersedesId = null)), provenance).single()
        assertThat(restated.recordedAt).isAfter(loaded)

        val latest = store.query(TimeSeriesQuery(ds, q1, q2))
        assertThat(latest.map { it.field to it.value.toPlainString() }).containsExactly(
            "irr" to "0.1200000000",
            "nav" to "90.0000000000",
            "nav" to "101.0000000000",
        )

        val asOfFirst = store.query(TimeSeriesQuery(ds, q1, q2, fields = setOf("nav"), asOfTime = loaded))
        assertThat(asOfFirst.map { it.value.toPlainString() }).containsExactly("90.0000000000", "100.0000000000")

        val changed = store.query(TimeSeriesQuery(ds, q1, q2, since = loaded))
        assertThat(changed).hasSize(1)
        assertThat(changed.single().value.toPlainString()).isEqualTo("101.0000000000")
        assertThat(store.query(TimeSeriesQuery(ds, q2.plusDays(1), q2.plusDays(1)))).isEmpty()
    }

    @Test
    fun `a refused observation rolls the batch back, and an unknown dataset has no tenant`() {
        val (ds, _) = dataset()
        assertThatThrownBy {
            store.write(listOf(nav(ds, q2, "100"), nav(ds, q2, "1", field = "Bad Field")), provenance)
        }.isInstanceOf(SQLException::class.java)
        assertThat(store.query(TimeSeriesQuery(ds, q1, q2))).isEmpty()
        assertThat(store.datasetTenant(UUID.randomUUID())).isNull()
        assertThatThrownBy { TimeSeriesQuery(ds, q2, q1) }.isInstanceOf(IllegalArgumentException::class.java)
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
