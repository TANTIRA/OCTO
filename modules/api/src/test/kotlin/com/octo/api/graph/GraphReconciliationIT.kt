package com.octo.api.graph

import com.octo.api.access.Tenant
import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.access.persistence.JdbcAccessStore
import com.octo.api.asset.Asset
import com.octo.api.asset.AssetProvenance
import com.octo.api.asset.AssetType
import com.octo.api.asset.persistence.JdbcAssetStore
import com.octo.iborcore.InstrumentFlow
import com.octo.iborcore.InstrumentFlowType
import com.octo.iborcore.persistence.JdbcInstrumentFlowStore
import com.octo.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/**
 * ADR-0004 acceptance box 3: the graph-ledger reconciliation report passes on seeded data, and each kind of drift
 * planted afterwards is reported in its own category, for its own tenant only. Real Postgres (V45) and Neo4j
 * (ontology 2.0.0). Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class GraphReconciliationIT {
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
        type: AssetType,
        name: String,
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

    private fun sql(statement: String) = dataSource.connection.use { it.createStatement().execute(statement) }

    private fun cypher(
        driver: Driver,
        query: String,
        parameters: Map<String, Any> = emptyMap(),
    ) = driver.executableQuery(query).withParameters(parameters).execute()

    private fun driver(): Driver =
        GraphDatabase.driver("bolt://${neo4j.host}:${neo4j.getMappedPort(7687)}", AuthTokens.basic("neo4j", PASSWORD))

    @BeforeEach
    fun cleanSlate() {
        sql("delete from octo.graph_outbox")
        driver().use { driver ->
            cypher(driver, "MATCH (n) DETACH DELETE n")
            File(System.getProperty("ontology.dir"), "octo-investment.cypher")
                .readLines()
                .filterNot { it.trim().startsWith("//") }
                .joinToString("\n")
                .split(";")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { cypher(driver, it) }
        }
    }

    @Test
    fun `seeded tenants reconcile clean, and every planted drift is reported in its category for its tenant only`() {
        val a = tenant()
        val b = tenant()
        val fund = store(a, AssetType.FUND, "Same Fund LP")
        store(a, AssetType.INVESTMENT, "Acme Series B")
        val company = store(a, AssetType.OPERATING_COMPANY, "PT Acme")
        store(a, AssetType.OPERATING_COMPANY, "PT Acme Logistik", supersedes = company.id)
        store(b, AssetType.FUND, "Same Fund LP")

        driver().use { driver ->
            val projector = GraphProjector(JdbcGraphOutboxStore(dataSource), driver, "neo4j", null)
            projector.drain()
            val reconciler = GraphReconciler(dataSource, driver, "neo4j")

            val seededA = reconciler.reconcile(a)
            assertThat(seededA.discrepancies).isEmpty()
            assertThat(seededA.checked).isEqualTo(3) // fund, investment, one node for the company's lineage
            assertThat(reconciler.reconcile(b).clean).isTrue()

            // Plant one drift of every kind in tenant A.
            cypher(driver, "MATCH (n:Fund {octoId: \$id}) DETACH DELETE n", mapOf("id" to fund.id.toString()))
            cypher(driver, "MATCH (n:Party {octoId: \$id}) SET n.legalName = 'Tampered'", mapOf("id" to company.id.toString()))
            val stray = UUID.randomUUID()
            cypher(
                driver,
                "CREATE (:Fund {octoId: \$id, tenantId: \$t, legalName: 'Stray LP'})",
                mapOf(
                    "id" to stray.toString(),
                    "t" to a.toString(),
                ),
            )
            // A fork — two corrections of one row — would plant the last kind, but V49 pinned
            // supersedes_id unique (#566), so Postgres can no longer produce one; FORKED stays in
            // the reconciler for lineages that predate the constraint.
            projector.drain()
            val failedId = UUID.randomUUID()
            val stuckId = UUID.randomUUID()
            sql(
                """
                insert into octo.graph_outbox (tenant_id, aggregate_type, aggregate_id, op, payload, status, attempts, last_error, created_at)
                values ('$a', 'asset', '$failedId', 'upsert', '{}', 'failed', 8, 'Neo.ClientError.Schema.ConstraintValidationFailed', now()),
                       ('$a', 'asset', '$stuckId', 'upsert', '{}', 'pending', 0, null, now() - interval '1 hour')
                """.trimIndent(),
            )

            val driftA = reconciler.reconcile(a).discrepancies.associate { it.kind to it.octoId }
            assertThat(driftA)
                .containsEntry(GraphDiscrepancyKind.MISSING, fund.id)
                .containsEntry(GraphDiscrepancyKind.STALE, company.id)
                .containsEntry(GraphDiscrepancyKind.ORPHAN, stray)
                .containsEntry(GraphDiscrepancyKind.FAILED, failedId)
                .containsEntry(GraphDiscrepancyKind.STUCK, stuckId)
                .hasSize(GraphDiscrepancyKind.entries.size - 1)
            assertThat(reconciler.reconcile(b).clean).`as`("tenant B is untouched by tenant A's drift").isTrue()
        }
    }

    @Test
    fun `an upsert still in flight is neither missing nor stale until it outlives the stuck threshold`() {
        val tenantId = tenant()
        store(tenantId, AssetType.FUND, "Fund In Flight")
        driver().use { driver ->
            assertThat(GraphReconciler(dataSource, driver, "neo4j").reconcile(tenantId).clean).isTrue()
        }
    }

    /**
     * #565: a promoted flow projects its nodes and relation edges, reconciles clean, and every
     * broken piece — the relation's wallet-side edge, a missing instrument endpoint, a missing
     * flow node — reports against the source row.
     */
    @Test
    fun `a promoted flow reconciles clean and every drift in its wiring is reported`() {
        val tenantId = tenant()
        val wallet =
            "7VVV" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .replace(Regex("[0OIl]"), "A")
                    .take(39)
        val instrumentId = UUID.randomUUID()
        sql(
            "insert into octo.tracked_address (chain, address, tenant_id, source_system, correlation_id) " +
                "values ('solana', '$wallet', '$tenantId', 'it', '${UUID.randomUUID()}')",
        )
        val mint =
            "7VVV" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .replace(Regex("[0OIl]"), "A")
                    .take(39)
        sql(
            "insert into octo.instrument (id, external_key, chain, mint_address, instrument_kind, decimals, symbol, " +
                "source_system, actor, ingestion_run_id, correlation_id) values " +
                "('$instrumentId', 'solana:mint:$mint', 'solana', '$mint', 'spl-token', 6, 'TST', " +
                "'it', 'it', '${UUID.randomUUID()}', '${UUID.randomUUID()}')",
        )
        val flow =
            InstrumentFlow(
                id = UUID.randomUUID(),
                externalId = "sig-1:$wallet:bal:0",
                instrumentId = instrumentId,
                chain = "solana",
                wallet = wallet,
                tokenAccount = null,
                flowType = InstrumentFlowType.TRANSFER_IN,
                amountRaw = BigInteger("500000"),
                decimals = 6,
                occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                recordedAt = Instant.parse("2026-01-01T00:00:05Z"),
                slot = 123_456L,
                signature = "sig-1",
            )
        JdbcInstrumentFlowStore(dataSource, ::enqueueInstrumentFlowProjection)
            .insertFlow(flow, "it", UUID.randomUUID(), UUID.randomUUID())

        driver().use { driver ->
            GraphProjector(JdbcGraphOutboxStore(dataSource), driver, "neo4j", null).drain()
            val reconciler = GraphReconciler(dataSource, driver, "neo4j")

            val seeded = reconciler.reconcile(tenantId)
            assertThat(seeded.discrepancies).isEmpty()
            assertThat(seeded.checked).isEqualTo(3) // flow, wallet endpoint, instrument endpoint

            // Dropping the wallet-side edge is a wiring drift against the flow, not a node drift.
            cypher(
                driver,
                "MATCH (r:InstrumentFlowOf {flowOctoId: \$id})-[e:INSTRUMENT_FLOW_OF__WALLET_SIDE]->() DELETE e",
                mapOf("id" to flow.id.toString()),
            )
            assertThat(reconciler.reconcile(tenantId).discrepancies)
                .containsExactly(
                    GraphDiscrepancy(
                        GraphDiscrepancyKind.STALE,
                        "instrument-flow",
                        flow.id,
                        "instrument-flow-of missing wallet-side",
                    ),
                )
            assertThat(reconciler.reconcile(tenant()).clean).`as`("another tenant sees nothing").isTrue()

            // Deleting the flow's whole relation reports every role it carried.
            cypher(driver, "MATCH (r:InstrumentFlowOf {flowOctoId: \$id}) DETACH DELETE r", mapOf("id" to flow.id.toString()))
            assertThat(reconciler.reconcile(tenantId).discrepancies.single())
                .isEqualTo(
                    GraphDiscrepancy(
                        GraphDiscrepancyKind.STALE,
                        "instrument-flow",
                        flow.id,
                        "instrument-flow-of missing relation+instrument-side+wallet-side",
                    ),
                )

            // The global endpoint's absence reports as a missing instrument, not a flow problem.
            cypher(driver, "MATCH (i:Instrument {instrumentId: \$id}) DETACH DELETE i", mapOf("id" to instrumentId.toString()))
            assertThat(reconciler.reconcile(tenantId).discrepancies)
                .containsExactlyInAnyOrder(
                    GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "instrument", instrumentId, "endpoint node absent"),
                    GraphDiscrepancy(
                        GraphDiscrepancyKind.STALE,
                        "instrument-flow",
                        flow.id,
                        "instrument-flow-of missing relation+instrument-side+wallet-side",
                    ),
                )
        }
    }

    private companion object {
        const val PASSWORD = "octo-graph-recon-password"

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("octo")
                .withUsername("octo")
                .withPassword("octo")

        @Container
        @JvmStatic
        val neo4j =
            GenericContainer(DockerImageName.parse("neo4j:2025.12.1-community"))
                .withEnv("NEO4J_AUTH", "neo4j/$PASSWORD")
                .withExposedPorts(7687)
    }
}
