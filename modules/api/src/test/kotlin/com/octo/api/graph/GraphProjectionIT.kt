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
import com.octo.iborcore.instrumentFlowOfGraphId
import com.octo.iborcore.persistence.JdbcInstrumentFlowStore
import com.octo.iborcore.walletGraphId
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The projector end to end against real Postgres (V45) and Neo4j (ontology 2.0.0 constraints): it lands one node per
 * asset lineage idempotently and in order, keys nodes per tenant, loses nothing to a graph outage, and spends a refused
 * write's retry budget into `failed`. Outbox semantics on their own are GraphOutboxStoreIT. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class GraphProjectionIT {
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

    private fun asset(
        tenantId: UUID,
        type: AssetType,
        name: String,
        supersedes: UUID? = null,
    ) = Asset(
        UUID.randomUUID(),
        tenantId,
        type,
        "private-equity",
        name,
        supersedesId = supersedes,
        rationale = supersedes?.let { "renamed" },
    )

    private fun store(asset: Asset) = assets.create(asset, emptyList(), provenance, TenantScope.Tenants(listOf(asset.tenantId)))

    private fun projector(
        driver: Driver,
        maxAttempts: Int = 8,
    ) = GraphProjector(JdbcGraphOutboxStore(dataSource, maxAttempts = maxAttempts, maxBackoff = Duration.ZERO), driver, "neo4j", null)

    private fun outbox(aggregateId: UUID): List<Pair<String, Int>> =
        dataSource.connection.use { connection ->
            val sql = "select status, attempts from octo.graph_outbox where aggregate_id = ? order by seq"
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, aggregateId)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) to rows.getInt(2) else null }.toList()
                }
            }
        }

    private fun nodes(
        label: String,
        octoId: UUID,
    ): List<Map<String, Any>> =
        driver().use { driver ->
            driver
                .executableQuery("MATCH (n:$label {octoId: \$id}) RETURN properties(n) AS p, labels(n) AS l")
                .withParameters(mapOf("id" to octoId.toString()))
                .execute()
                .records()
                .map { it["p"].asMap() + ("labels" to it["l"].asList { v -> v.asString() }.toSet()) }
        }

    @BeforeEach
    fun applySchema() {
        driver().use { driver ->
            driver.executableQuery("MATCH (n) DETACH DELETE n").execute()
            File(System.getProperty("ontology.dir"), "octo-investment.cypher")
                .readLines()
                .filterNot { it.trim().startsWith("//") }
                .joinToString("\n")
                .split(";")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { driver.executableQuery(it).execute() }
        }
    }

    @Test
    fun `an asset and its graph intent commit together, and the projector lands one node per lineage`() {
        val tenantId = tenant()
        val original = asset(tenantId, AssetType.OPERATING_COMPANY, "PT Acme")
        store(original)
        assertThat(outbox(original.id)).containsExactly("pending" to 0)

        val corrected = asset(tenantId, AssetType.OPERATING_COMPANY, "PT Acme Logistik", supersedes = original.id)
        store(corrected)
        // Both upserts target the lineage root, and the later one waits for the earlier one.
        assertThat(outbox(original.id)).containsExactly("pending" to 0, "pending" to 0)

        driver().use { driver -> assertThat(projector(driver).drain()).isEqualTo(2) }

        val node = nodes("Party", original.id).single()
        assertThat(node["legalName"]).isEqualTo("PT Acme Logistik")
        assertThat(node["tenantId"]).isEqualTo(tenantId.toString())
        assertThat(node["labels"]).isEqualTo(setOf("OperatingCompany", "Organization", "Party"))
        assertThat(outbox(original.id).map { it.first }).containsExactly("applied", "applied")

        // A replayed row is a no-op: still one node with the newest state.
        dataSource.connection.use { it.createStatement().execute("update octo.graph_outbox set status = 'pending', applied_at = null") }
        driver().use { driver -> projector(driver).drain() }
        assertThat(nodes("Party", original.id).single()["legalName"]).isEqualTo("PT Acme Logistik")
    }

    @Test
    fun `two tenants may each hold the same fund`() {
        val a = tenant()
        val b = tenant()
        val fundA = asset(a, AssetType.FUND, "Same Fund LP")
        val fundB = asset(b, AssetType.FUND, "Same Fund LP")
        store(fundA)
        store(fundB)

        driver().use { driver -> projector(driver).drain() }
        assertThat(nodes("Fund", fundA.id).single()["tenantId"]).isEqualTo(a.toString())
        assertThat(nodes("Fund", fundB.id).single()["tenantId"]).isEqualTo(b.toString())
    }

    @Test
    fun `an unreachable graph hands rows back without spending attempts`() {
        val tenantId = tenant()
        val fund = asset(tenantId, AssetType.FUND, "Fund III")
        store(fund)

        GraphDatabase.driver("bolt://127.0.0.1:1", AuthTokens.basic("neo4j", PASSWORD)).use { down ->
            assertThat(projector(down).drain()).isZero()
        }
        assertThat(outbox(fund.id)).containsExactly("pending" to 0)

        driver().use { driver -> assertThat(projector(driver).drain()).isEqualTo(1) }
        assertThat(outbox(fund.id).single().first).isEqualTo("applied")
    }

    @Test
    fun `a write Neo4j refuses spends its retry budget and ends failed, never skipped`() {
        val tenantId = tenant()
        val first = asset(tenantId, AssetType.INVESTMENT, "Acme Series B")
        val clash = asset(tenantId, AssetType.INVESTMENT, "Acme Series B")
        store(first)
        store(clash)

        driver().use { driver ->
            val projector = projector(driver, maxAttempts = 2)
            projector.drain() // first lands; the clash violates (tenantId, displayName) and spends attempt 1
            projector.drain() // backoff is capped at zero here, so attempt 2 runs now and exhausts the budget
        }
        assertThat(outbox(first.id).single().first).isEqualTo("applied")
        assertThat(outbox(clash.id)).containsExactly("failed" to 2)
        assertThat(JdbcGraphOutboxStore(dataSource).stats().failed).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `a promoted flow projects its instrument, wallet, flow, and instrument-flow-of edges`() {
        val tenantId = tenant()
        val wallet = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
        track("solana", wallet, tenantId)
        val instrumentId = instrumentId("solana:native")
        val flows = JdbcInstrumentFlowStore(dataSource)
        val original = flow(instrumentId, "solana", wallet, "solana:sig-1:$wallet:bal:0", 500, 9)
        assertThat(flows.insertFlow(original, "helius-solana", UUID.randomUUID(), UUID.randomUUID())).isTrue()

        driver().use { driver ->
            val projecting = projector(driver)
            assertThat(projecting.drain()).isEqualTo(4)
            assertThat(nodes("InstrumentFlow", original.id).single()["monetaryAmount"]).isEqualTo("500")
            assertThat(nodes("Wallet", walletGraphId(tenantId, "solana", wallet)).single()["solanaAddress"]).isEqualTo(wallet)
            val instrument = nodes("Instrument", instrumentId).single()
            assertThat(instrument["instrumentId"]).isEqualTo("solana:native")
            assertThat(instrument).doesNotContainKey("tenantId")
            val relationId = instrumentFlowOfGraphId(original.id)
            assertThat(edges(relationId)).containsExactlyInAnyOrder(
                Triple("FLOW_SIDE", original.id, setOf("InstrumentFlow")),
                Triple("INSTRUMENT_SIDE", instrumentId, setOf("Instrument")),
                Triple("WALLET_SIDE", walletGraphId(tenantId, "solana", wallet), setOf("Wallet")),
            )
            assertThat(GraphReconciler(dataSource, driver, "neo4j").reconcile(tenantId).clean).isTrue()

            val corrected =
                original.copy(
                    id = UUID.randomUUID(),
                    externalId = "solana:sig-1:$wallet:bal:0:fix",
                    amountRaw = BigInteger("150"),
                    supersedesId = original.id,
                    rationale = "restated amount",
                )
            assertThat(flows.insertFlow(corrected, "helius-solana", UUID.randomUUID(), UUID.randomUUID())).isTrue()
            assertThat(projecting.drain()).isEqualTo(4)
            assertThat(nodes("InstrumentFlow", original.id).single()["monetaryAmount"]).isEqualTo("150")
            assertThat(nodes("InstrumentFlow", corrected.id)).isEmpty()
            assertThat(GraphReconciler(dataSource, driver, "neo4j").reconcile(tenantId).clean).isTrue()

            driver
                .executableQuery("MATCH (:InstrumentFlowOf {octoId: \$id})-[r:FLOW_SIDE]->() DELETE r")
                .withParameters(mapOf("id" to relationId.toString()))
                .execute()
            assertThat(GraphReconciler(dataSource, driver, "neo4j").reconcile(tenantId).discrepancies)
                .singleElement()
                .extracting { it.kind to it.octoId }
                .isEqualTo(GraphDiscrepancyKind.STALE to relationId)
        }
    }

    @Test
    fun `an EVM flow projects an EvmWallet and an EvmContract`() {
        val tenantId = tenant()
        val wallet = "0x1111111111111111111111111111111111111111"
        track("arbitrum-one", wallet, tenantId)
        val instrumentId = instrumentId("arbitrum-one:contract:0xaf88d065e77c8cc2239327c5edb3a432268e5831")
        val promoted =
            flow(
                instrumentId,
                "arbitrum-one",
                wallet,
                "arbitrum-one:0xtx:$wallet:log:1",
                150,
                6,
            )
        JdbcInstrumentFlowStore(dataSource).insertFlow(promoted, "rpc-arbitrum-one", UUID.randomUUID(), UUID.randomUUID())

        driver().use { driver ->
            assertThat(projector(driver).drain()).isEqualTo(4)
            assertThat(nodes("EvmWallet", walletGraphId(tenantId, "arbitrum-one", wallet)).single()["evmAddress"]).isEqualTo(wallet)
            val contract = nodes("EvmContract", instrumentId).single()
            assertThat(contract["labels"]).isEqualTo(setOf("EvmContract", "Instrument"))
            assertThat(contract).doesNotContainKey("tenantId")
            assertThat(GraphReconciler(dataSource, driver, "neo4j").reconcile(tenantId).clean).isTrue()
        }
    }

    private fun track(
        chain: String,
        address: String,
        tenantId: UUID,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "insert into octo.tracked_address (chain, address, tenant_id, source_system, correlation_id) values (?, ?, ?, 'test', ?)",
                ).use { statement ->
                    statement.setString(1, chain)
                    statement.setString(2, address)
                    statement.setObject(3, tenantId)
                    statement.setObject(4, UUID.randomUUID())
                    statement.executeUpdate()
                }
        }
    }

    private fun instrumentId(externalKey: String): UUID =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select id from octo.instrument where external_key = ?").use { statement ->
                statement.setString(1, externalKey)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "instrument $externalKey missing" }
                    rows.getObject(1, UUID::class.java)
                }
            }
        }

    private fun flow(
        instrumentId: UUID,
        chain: String,
        wallet: String,
        externalId: String,
        amount: Long,
        decimals: Int,
    ) = InstrumentFlow(
        id = UUID.randomUUID(),
        externalId = externalId,
        instrumentId = instrumentId,
        chain = chain,
        wallet = wallet,
        tokenAccount = null,
        flowType = InstrumentFlowType.TRANSFER_IN,
        amountRaw = BigInteger.valueOf(amount),
        decimals = decimals,
        occurredAt = Instant.parse("2026-02-01T09:30:00Z"),
        recordedAt = Instant.parse("2026-02-01T09:30:41Z"),
        slot = 100,
        signature = "sig",
    )

    private fun edges(octoId: UUID): List<Triple<String, UUID, Set<String>>> =
        driver().use { driver ->
            driver
                .executableQuery("MATCH (n {octoId: \$id})-[r]->(m) RETURN type(r) AS t, m.octoId AS id, labels(m) AS l")
                .withParameters(mapOf("id" to octoId.toString()))
                .execute()
                .records()
                .map { record ->
                    Triple(
                        record["t"].asString(),
                        UUID.fromString(record["id"].asString()),
                        record["l"].asList { it.asString() }.toSet(),
                    )
                }
        }

    private fun driver(): Driver =
        GraphDatabase.driver("bolt://${neo4j.host}:${neo4j.getMappedPort(7687)}", AuthTokens.basic("neo4j", PASSWORD))

    private companion object {
        const val PASSWORD = "octo-graph-it-password"

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
