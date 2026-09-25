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
 * Runs the real Flyway migrations and exercises the V11 `mesta.asset` and `mesta.asset_xref` invariants (#103):
 * ontology types only, ISO region, append-only, supersession with a rationale, one identifier per lineage,
 * LEI shape. Same shape as `ValuationMigrationIT`. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class AssetMigrationIT {
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
        for (table in listOf("asset", "asset_xref")) {
            assertThat(count("select count(*) from pg_tables where schemaname = 'mesta' and tablename = '$table'")).isEqualTo(1)
            assertThat(count("select count(*) from pg_trigger where tgname = '${table}_append_only'")).isEqualTo(1)
        }
    }

    @Test
    fun `only ontology types, ISO regions and named tags are accepted, and rows are never edited`() {
        assertThatThrownBy { asset(type = "equity") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { asset(region = "usa") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { asset(tags = "{growth,\"\"}") }.isInstanceOf(SQLException::class.java)
        val id = asset(region = "ID", tags = "{growth,sea}")
        assertThatThrownBy { execute("update mesta.asset set region = 'SG' where id = '$id'") }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("append-only")
        assertThatThrownBy { asset(supersedes = id, rationale = " ") }.isInstanceOf(SQLException::class.java)
        asset(supersedes = id, rationale = "domicile corrected after KYC refresh")
    }

    @Test
    fun `an identifier names one lineage and a LEI has its shape`() {
        val a = asset()
        val b = asset()
        xref(a, "lei", "5493001KJTIIGC8Y1R12")
        assertThatThrownBy { xref(b, "lei", "5493001KJTIIGC8Y1R12") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { xref(b, "lei", "not-a-lei") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { xref(b, "Vendor:Preqin", "x") }.isInstanceOf(SQLException::class.java)
        xref(b, "vendor:preqin", "PQ-1")
        assertThatThrownBy { execute("delete from mesta.asset_xref where asset_id = '$b'") }.isInstanceOf(SQLException::class.java)
    }

    private fun asset(
        type: String = "fund",
        region: String? = null,
        tags: String = "{}",
        supersedes: UUID? = null,
        rationale: String? = null,
    ): UUID =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    insert into mesta.asset (tenant_id, asset_type, asset_class, display_name, region, tags, supersedes_id, rationale,
                                             source_system, actor, correlation_id)
                    values (?, ?, 'private-equity', 'Fund I', ?, ?::text[], ?, ?, 'test', 'integration-test', gen_random_uuid())
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    listOf(tenant, type, region, tags, supersedes, rationale).forEachIndexed { i, v -> statement.setObject(i + 1, v) }
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
                }
        }

    private fun xref(
        assetId: UUID,
        scheme: String,
        value: String,
    ) = execute("insert into mesta.asset_xref (asset_id, scheme, value) values ('$assetId', '$scheme', '$value')")

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
