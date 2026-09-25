package com.mesta.asset.api.asset

import com.mesta.asset.api.access.Tenant
import com.mesta.asset.api.access.persistence.AccessProvenance
import com.mesta.asset.api.access.persistence.JdbcAccessStore
import com.mesta.asset.api.asset.persistence.JdbcAssetStore
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

/** `JdbcAssetStore` against the real V11 schema: round-trip, lineage identifiers, and atomic refusal. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class AssetStoreIT {
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
    private val store by lazy { JdbcAssetStore(dataSource) }
    private val provenance = AssetProvenance("integration-test", "quant-1", UUID.randomUUID())
    private val tenantId by lazy {
        Tenant(UUID.randomUUID(), "t-${UUID.randomUUID().toString().take(8)}", "Tenant")
            .also { JdbcAccessStore(dataSource).createTenant(it, AccessProvenance("integration-test", UUID.randomUUID())) }
            .id
    }

    private fun fund(supersedes: UUID? = null) =
        Asset(
            UUID.randomUUID(),
            tenantId,
            AssetType.FUND,
            "private-equity",
            "Fund I",
            region = "ID",
            tags =
                listOf(
                    "growth",
                    "sea",
                ),
            supersedesId = supersedes,
            rationale =
                supersedes?.let {
                    "renamed"
                },
        )

    @Test
    fun `an asset round-trips and a superseding row inherits the lineage's identifiers`() {
        val original = fund()
        store.create(original, listOf(Identifier("lei", "5493001KJTIIGC8Y1R12"), Identifier("crm", "C-1")), provenance)
        val loaded = store.load(original.id)!!
        assertThat(loaded.asset).isEqualTo(original)
        assertThat(loaded.identifiers).containsExactly(Identifier("crm", "C-1"), Identifier("lei", "5493001KJTIIGC8Y1R12"))
        assertThat(loaded.supersededBy).isNull()

        val corrected = fund(supersedes = original.id).copy(displayName = "Fund I (Feeder)")
        store.create(corrected, listOf(Identifier("vendor:preqin", "PQ-1")), provenance)
        assertThat(store.load(original.id)!!.supersededBy).isEqualTo(corrected.id)
        assertThat(store.load(corrected.id)!!.identifiers.map { it.scheme }).containsExactly("crm", "lei", "vendor:preqin")
    }

    @Test
    fun `a refused identifier rolls the asset back, and an unknown id is null`() {
        val asset = fund()
        assertThatThrownBy { store.create(asset, listOf(Identifier("lei", "bad")), provenance) }.isInstanceOf(SQLException::class.java)
        assertThat(store.load(asset.id)).isNull()
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
