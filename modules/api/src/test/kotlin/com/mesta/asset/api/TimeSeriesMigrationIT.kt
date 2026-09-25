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
 * Runs the real Flyway migrations and exercises the V12 `mesta.dataset` and `mesta.timeseries_observation`
 * invariants (#104): registered datasets only, one name per tenant, shaped fields, append-only, distinct
 * recorded_at per row, supersession with a rationale. Same shape as `ValuationMigrationIT`. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class TimeSeriesMigrationIT {
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
    fun `migration creates both tables with their append-only triggers`() {
        for (table in listOf("dataset", "timeseries_observation")) {
            assertThat(count("select count(*) from pg_tables where schemaname = 'mesta' and tablename = '$table'")).isEqualTo(1)
            assertThat(count("select count(*) from pg_trigger where tgname = '${table}_append_only'")).isEqualTo(1)
        }
    }

    @Test
    fun `a dataset has a shaped name, one per tenant, and is never edited`() {
        val id = dataset("nav.quarterly")
        assertThatThrownBy { dataset("nav.quarterly") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { dataset("NAV Quarterly") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { dataset("fx.daily", currency = "usd") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { execute("update mesta.dataset set unit = 'x' where id = '$id'") }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("append-only")
    }

    @Test
    fun `observations need a registered dataset, a shaped field, distinct recorded_at, and a rationale to supersede`() {
        assertThatThrownBy { observation(UUID.randomUUID(), "fund-1", "nav", "100") }.isInstanceOf(SQLException::class.java)
        val ds = dataset("nav.quarterly.${UUID.randomUUID().toString().take(8)}")
        val first = observation(ds, "fund-1", "nav", "100")
        val second = observation(ds, "fund-1", "nav", "101") // same key and date, later clock_timestamp: a new version
        assertThat(second).isNotEqualTo(first)
        assertThatThrownBy { observation(ds, "fund-1", "NAV", "1") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { observation(ds, " ", "nav", "1") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            observation(
                ds,
                "fund-1",
                "nav",
                "102",
                supersedes = first,
                rationale = " ",
            )
        }.isInstanceOf(SQLException::class.java)
        observation(ds, "fund-1", "nav", "102", supersedes = first, rationale = "restated after audit")
        assertThatThrownBy {
            execute(
                "delete from mesta.timeseries_observation where dataset_id = '$ds'",
            )
        }.isInstanceOf(SQLException::class.java)
    }

    private fun dataset(
        name: String,
        currency: String? = "USD",
    ): UUID =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    insert into mesta.dataset (tenant_id, name, description, unit, currency_code, source_system, actor, correlation_id)
                    values (?, ?, 'Quarterly NAV', 'currency', ?, 'test', 'integration-test', gen_random_uuid())
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    listOf(tenant, name, currency).forEachIndexed { i, v -> statement.setObject(i + 1, v) }
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
                }
        }

    private fun observation(
        datasetId: UUID,
        seriesKey: String,
        field: String,
        value: String,
        supersedes: UUID? = null,
        rationale: String? = null,
    ): UUID =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    insert into mesta.timeseries_observation (dataset_id, series_key, field, effective_date, value, supersedes_id, rationale,
                                                              source_system, actor, ingestion_run_id, correlation_id)
                    values (?, ?, ?, '2026-06-30', ?::numeric, ?, ?, 'test', 'integration-test', gen_random_uuid(), gen_random_uuid())
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    listOf(datasetId, seriesKey, field, value, supersedes, rationale).forEachIndexed {
                        i,
                        v,
                        ->
                        statement.setObject(i + 1, v)
                    }
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
                }
        }

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
