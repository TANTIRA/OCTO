package com.octo.api.graph

import com.octo.iborcore.InstrumentFlow
import com.octo.iborcore.InstrumentFlowType
import com.octo.iborcore.ProjectedInstrument
import com.octo.iborcore.instrumentFlowProjection
import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import org.neo4j.driver.Driver
import org.neo4j.driver.QueryConfig
import org.neo4j.driver.Record
import org.neo4j.driver.Value
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** One way the graph and its Postgres sources disagree (ADR-0004 acceptance box 3, #308). */
enum class GraphDiscrepancyKind(
    val wireValue: String,
) {
    /** The source has a node to project, nothing is pending for it, and the graph has no such node. */
    MISSING("missing"),

    /** The node exists but its labels, tenant or projected properties differ from the source's current state. */
    STALE("stale"),

    /** The graph holds a node of this tenant whose octoId no source row accounts for. */
    ORPHAN("orphan"),

    /** A lineage has more than one current row, so there is no single state to project. */
    FORKED("forked"),

    /** An outbox row spent its retry budget. */
    FAILED("failed"),

    /** An outbox row has waited longer than the stuck threshold. */
    STUCK("stuck"),
}

data class GraphDiscrepancy(
    val kind: GraphDiscrepancyKind,
    val aggregateType: String,
    val octoId: UUID,
    val detail: String,
)

/** A tenant's reconciliation: how many source nodes were checked and every disagreement found. Clean means none. */
data class GraphReconciliation(
    val tenantId: UUID,
    val checked: Int,
    val discrepancies: List<GraphDiscrepancy>,
) {
    val clean: Boolean get() = discrepancies.isEmpty()
}

/** A node as its source says it should look. [edges] maps each role type to the target octoId. */
private data class ExpectedNode(
    val octoId: UUID,
    val aggregateType: String,
    val labels: Set<String>,
    val properties: Map<String, String>,
    val edges: Map<String, String> = emptyMap(),
)

/**
 * Compares one tenant's graph with the Postgres rows it is projected from (ADR-0004 amendment, #308). Postgres is the
 * ledger of record, so every disagreement is reported against it. Rows still in flight in the outbox are not counted
 * as missing or stale until they fail or outlive [stuckAfter]. A lineage with two current rows is reported as
 * `forked`, never resolved by guessing.
 */
class GraphReconciler(
    private val dataSource: DataSource,
    private val driver: Driver,
    database: String,
    private val stuckAfter: Duration = Duration.ofMinutes(15),
) {
    private val config = QueryConfig.builder().withDatabase(database).build()

    fun reconcile(tenantId: UUID): GraphReconciliation {
        val scope = TenantScope.Tenants(listOf(tenantId))
        val (assets, assetForks) = expectedAssets(tenantId, scope)
        val (onchain, flowForks) = expectedOnchain(tenantId, scope)
        val expected = assets + onchain
        val inFlight = inFlight(tenantId, scope)
        val tenantNodes = graphNodes(tenantId)
        val instrumentIds = expected.values.filter { it.aggregateType == "instrument" }.map { it.octoId }
        val actual = tenantNodes + instrumentNodes(instrumentIds)
        val discrepancies = mutableListOf<GraphDiscrepancy>()

        assetForks.forEach { (root, heads) ->
            discrepancies +=
                GraphDiscrepancy(GraphDiscrepancyKind.FORKED, "asset", root, "current rows $heads")
        }
        flowForks.forEach { (root, heads) ->
            discrepancies +=
                GraphDiscrepancy(GraphDiscrepancyKind.FORKED, "instrument-flow", root, "current rows $heads")
        }
        inFlight.forEach { row ->
            when {
                row.status == "failed" ->
                    discrepancies +=
                        GraphDiscrepancy(GraphDiscrepancyKind.FAILED, row.aggregateType, row.aggregateId, row.detail)
                row.ageSeconds > stuckAfter.seconds ->
                    discrepancies +=
                        GraphDiscrepancy(GraphDiscrepancyKind.STUCK, row.aggregateType, row.aggregateId, "pending ${row.ageSeconds}s")
            }
        }
        val awaiting = inFlight.map { it.aggregateId }.toSet()
        for (node in expected.values) {
            if (node.octoId in awaiting) continue
            val found = actual[node.octoId]
            when {
                found == null -> discrepancies += GraphDiscrepancy(GraphDiscrepancyKind.MISSING, node.aggregateType, node.octoId, "no node")
                disagrees(node, found) ->
                    discrepancies +=
                        GraphDiscrepancy(
                            GraphDiscrepancyKind.STALE,
                            node.aggregateType,
                            node.octoId,
                            "graph ${found.labels} ${found.properties} ${found.edges}",
                        )
            }
        }
        val forked = assetForks.keys + flowForks.keys
        val accounted = expected.keys + forked + awaiting
        tenantNodes.values.filter { it.octoId !in accounted }.forEach {
            discrepancies += GraphDiscrepancy(GraphDiscrepancyKind.ORPHAN, "unknown", it.octoId, "labels ${it.labels}")
        }
        return GraphReconciliation(tenantId, expected.size + assetForks.size + flowForks.size, discrepancies)
    }

    private fun disagrees(
        expected: ExpectedNode,
        found: ActualNode,
    ) = found.labels != expected.labels ||
        expected.properties.any { (key, value) -> found.properties[key] != value } ||
        found.edges != expected.edges

    /** Each asset lineage's current row, keyed by lineage root; lineages with more than one current row come back as forks. */
    private fun expectedAssets(
        tenantId: UUID,
        scope: TenantScope,
    ): Pair<Map<UUID, ExpectedNode>, Map<UUID, List<UUID>>> {
        val sql =
            """
            with recursive lineage (root, id) as (
                select id, id from octo.asset where tenant_id = ? and supersedes_id is null
                union all
                select l.root, a.id from octo.asset a join lineage l on a.supersedes_id = l.id)
            select l.root, a.id, a.asset_type, a.display_name
            from lineage l join octo.asset a on a.id = l.id
            where not exists (select 1 from octo.asset s where s.supersedes_id = a.id)
            order by l.root, a.recorded_at
            """.trimIndent()
        val heads =
            dataSource
                .scoped(scope) { connection ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setObject(1, tenantId)
                        statement.executeQuery().use { rows ->
                            generateSequence {
                                if (rows.next()) {
                                    Triple(
                                        rows.getObject(1, UUID::class.java),
                                        rows.getObject(2, UUID::class.java),
                                        rows.getString(3) to rows.getString(4),
                                    )
                                } else {
                                    null
                                }
                            }.toList()
                        }
                    }
                }.groupBy({ it.first })
        val (single, forked) = heads.entries.partition { it.value.size == 1 }
        val expected =
            single.associate { (root, rows) ->
                val (kind, name) = rows.single().third
                val projection = requireNotNull(PROJECTIONS["asset" to kind]) { "no graph projection for asset/$kind" }
                root to
                    ExpectedNode(
                        root,
                        "asset",
                        projection.labelSet,
                        projection.properties.associateWith { name } + ("tenantId" to tenantId.toString()),
                    )
            }
        return expected to forked.associate { (root, rows) -> root to rows.map { it.second } }
    }

    /**
     * Current instrument-flow lineages of this tenant, plus the wallet, instrument, and relation each one projects.
     * Instruments are global reference data (no `tenantId`); the other three nodes carry the tenant. A lineage with
     * two current rows is a fork and is not given a node.
     */
    private fun expectedOnchain(
        tenantId: UUID,
        scope: TenantScope,
    ): Pair<Map<UUID, ExpectedNode>, Map<UUID, List<UUID>>> {
        val sql =
            """
            with recursive lineage (root, id) as (
                select f.id, f.id
                  from octo.instrument_flow f
                  join octo.tracked_address ta
                    on ta.chain = f.chain and ta.address = f.wallet and ta.tenant_id = ?
                 where f.supersedes_id is null
                union all
                select l.root, c.id
                  from octo.instrument_flow c
                  join lineage l on c.supersedes_id = l.id)
            select l.root,
                   f.id, f.external_id, f.instrument_id, f.chain, f.wallet, f.token_account, f.flow_type,
                   f.amount_raw, f.decimals, f.occurred_at, f.recorded_at, f.slot, f.signature,
                   f.supersedes_id, f.rationale,
                   i.external_key, i.chain as instrument_chain, i.mint_address, i.instrument_kind,
                   i.decimals as instrument_decimals
              from lineage l
              join octo.instrument_flow f on f.id = l.id
              join octo.instrument i on i.id = f.instrument_id
             where not exists (select 1 from octo.instrument_flow s where s.supersedes_id = f.id)
             order by l.root, f.recorded_at
            """.trimIndent()
        val heads =
            dataSource
                .scoped(scope) { connection ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setObject(1, tenantId)
                        statement.executeQuery().use { rows ->
                            generateSequence {
                                if (!rows.next()) {
                                    null
                                } else {
                                    val flow =
                                        InstrumentFlow(
                                            id = rows.getObject("id", UUID::class.java),
                                            externalId = rows.getString("external_id"),
                                            instrumentId = rows.getObject("instrument_id", UUID::class.java),
                                            chain = rows.getString("chain"),
                                            wallet = rows.getString("wallet"),
                                            tokenAccount = rows.getString("token_account"),
                                            flowType = InstrumentFlowType.entries.first { it.wireValue == rows.getString("flow_type") },
                                            amountRaw = rows.getBigDecimal("amount_raw").toBigIntegerExact(),
                                            decimals = rows.getInt("decimals"),
                                            occurredAt = rows.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                                            recordedAt = rows.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                                            slot = rows.getLong("slot").takeIf { !rows.wasNull() },
                                            signature = rows.getString("signature"),
                                            supersedesId = rows.getObject("supersedes_id", UUID::class.java),
                                            rationale = rows.getString("rationale"),
                                        )
                                    Triple(
                                        rows.getObject("root", UUID::class.java),
                                        flow,
                                        ProjectedInstrument(
                                            id = flow.instrumentId,
                                            externalKey = rows.getString("external_key"),
                                            chain = rows.getString("instrument_chain"),
                                            mintAddress = rows.getString("mint_address"),
                                            kind = rows.getString("instrument_kind"),
                                            decimals = rows.getInt("instrument_decimals"),
                                        ),
                                    )
                                }
                            }.toList()
                        }
                    }
                }.groupBy { it.first }
        val (single, forked) = heads.entries.partition { it.value.size == 1 }
        val expected =
            single
                .flatMap { (root, rows) ->
                    val (_, flow, instrument) = rows.single()
                    instrumentFlowProjection(tenantId, flow, root, instrument).map { spec ->
                        val projection =
                            requireNotNull(PROJECTIONS[spec.aggregateType to spec.kind]) {
                                "no graph projection for ${spec.aggregateType}/${spec.kind}"
                            }
                        val properties =
                            spec.properties.filterKeys { it in projection.properties || it in projection.optionalProperties }
                        val withTenant =
                            if (projection.tenantScoped) properties + ("tenantId" to tenantId.toString()) else properties
                        ExpectedNode(
                            spec.aggregateId,
                            spec.aggregateType,
                            projection.labelSet,
                            withTenant,
                            spec.endpoints.mapValues { it.value.toString() },
                        )
                    }
                }.associateBy { it.octoId }
        return expected to forked.associate { (root, rows) -> root to rows.map { it.second.id } }
    }

    private data class InFlight(
        val aggregateType: String,
        val aggregateId: UUID,
        val status: String,
        val ageSeconds: Long,
        val detail: String,
    )

    /** Outbox rows not yet applied: pending ones are in flight, failed ones are discrepancies. */
    private fun inFlight(
        tenantId: UUID,
        scope: TenantScope,
    ): List<InFlight> =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    """
                    select aggregate_type, aggregate_id, status, extract(epoch from clock_timestamp() - created_at)::bigint, attempts, last_error
                    from octo.graph_outbox where tenant_id = ? and status <> 'applied' order by seq
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.executeQuery().use { rows ->
                        generateSequence {
                            if (rows.next()) {
                                InFlight(
                                    rows.getString(1),
                                    rows.getObject(2, UUID::class.java),
                                    rows.getString(3),
                                    rows.getLong(4),
                                    "attempts ${rows.getInt(5)}: ${rows.getString(6) ?: "-"}",
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private data class ActualNode(
        val octoId: UUID,
        val labels: Set<String>,
        val properties: Map<String, String>,
        val edges: Map<String, String> = emptyMap(),
    )

    /** Every node this tenant owns in the graph, keyed by octoId, with the role edges leaving it. */
    private fun graphNodes(tenantId: UUID): Map<UUID, ActualNode> {
        val nodes =
            readNodes(
                "MATCH (n) WHERE n.tenantId = \$tenantId AND n.octoId IS NOT NULL RETURN n.octoId AS id, labels(n) AS labels, properties(n) AS props",
                mapOf("tenantId" to tenantId.toString()),
            )
        val edges =
            driver
                .executableQuery(
                    """
                    MATCH (n)-[r]->(m)
                    WHERE n.tenantId = ${'$'}tenantId AND n.octoId IS NOT NULL AND m.octoId IS NOT NULL
                    RETURN n.octoId AS id, type(r) AS type, m.octoId AS target
                    """.trimIndent(),
                ).withParameters(mapOf("tenantId" to tenantId.toString()))
                .withConfig(config)
                .execute()
                .records()
                .groupBy({ UUID.fromString(it["id"].asString()) }) {
                    it["type"].asString() to it["target"].asString()
                }.mapValues { (_, pairs) -> pairs.toMap() }
        return nodes.mapValues { (id, node) -> node.copy(edges = edges[id].orEmpty()) }
    }

    /**
     * Global instrument nodes referenced by this tenant. They carry no `tenantId`, so they are absent from
     * [graphNodes]; fetching them by id keeps another tenant's instruments from looking like this tenant's orphans.
     */
    private fun instrumentNodes(ids: Collection<UUID>): Map<UUID, ActualNode> {
        if (ids.isEmpty()) return emptyMap()
        return readNodes(
            "MATCH (n:Instrument) WHERE n.octoId IN \$ids RETURN n.octoId AS id, labels(n) AS labels, properties(n) AS props",
            mapOf("ids" to ids.map { it.toString() }),
        )
    }

    private fun readNodes(
        cypher: String,
        parameters: Map<String, Any>,
    ): Map<UUID, ActualNode> =
        driver
            .executableQuery(cypher)
            .withParameters(parameters)
            .withConfig(config)
            .execute()
            .records()
            .associate(::toNode)

    private fun toNode(record: Record): Pair<UUID, ActualNode> {
        val id = UUID.fromString(record["id"].asString())
        return id to
            ActualNode(
                id,
                record["labels"].asList(Value::asString).toSet(),
                record["props"].asMap { it.asObject()?.toString().orEmpty() }.minus("octoId"),
            )
    }
}
