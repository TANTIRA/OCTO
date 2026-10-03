package com.octo.api.graph

import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import org.neo4j.driver.Driver
import org.neo4j.driver.QueryConfig
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

/** A node as its source says it should look. */
private data class ExpectedNode(
    val octoId: UUID,
    val aggregateType: String,
    val labels: Set<String>,
    val properties: Map<String, String>,
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
        val (assetExpected, forked) = expectedAssets(tenantId, scope)
        val flows = expectedInstrumentFlows(tenantId, scope)
        val inFlight = inFlight(tenantId, scope)
        val actual = graphNodes(tenantId)
        val discrepancies = mutableListOf<GraphDiscrepancy>()

        forked.forEach { (root, heads) ->
            discrepancies +=
                GraphDiscrepancy(GraphDiscrepancyKind.FORKED, "asset", root, "current rows $heads")
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
        // Asset lineage roots, the tenant's flows, and the wallets those flows touch all carry
        // octoId, so the same missing/stale/orphan accounting covers them (#565). The reified
        // relation and the global instrument carry none — they are checked per flow below.
        val expected =
            assetExpected +
                flows.mapValues { it.value.node } +
                flows.values.associate { it.wallet.octoId to it.wallet }
        for (node in expected.values) {
            if (node.octoId in awaiting) continue
            val found = actual[node.octoId]
            when {
                found == null -> discrepancies += GraphDiscrepancy(GraphDiscrepancyKind.MISSING, node.aggregateType, node.octoId, "no node")
                found.labels != node.labels || node.properties.any { (k, v) -> found.properties[k] != v } ->
                    discrepancies +=
                        GraphDiscrepancy(
                            GraphDiscrepancyKind.STALE,
                            node.aggregateType,
                            node.octoId,
                            "graph ${found.labels} ${found.properties}",
                        )
            }
        }
        val activeFlows = flows.values.filter { it.node.octoId !in awaiting }
        val instrumentIds = activeFlows.map { it.instrumentId }.toSet()
        absentInstruments(instrumentIds).forEach { id ->
            discrepancies += GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "instrument", id, "endpoint node absent")
        }
        val wiring = flowWiring(tenantId, activeFlows.map { it.node.octoId })
        for (flow in activeFlows) {
            if (actual[flow.node.octoId] == null) continue // the flow's missing report already carries it
            val ends = wiring[flow.node.octoId]
            val broken =
                when {
                    ends == null -> listOf("relation")
                    else ->
                        buildList {
                            if (!ends.related) add("relation")
                            if (ends.instrumentId != flow.instrumentId.toString()) add("instrument-side")
                            if (ends.walletAddress != flow.walletAddress || ends.walletLabels != flow.wallet.labels) add("wallet-side")
                        }
                }
            if (broken.isNotEmpty()) {
                discrepancies +=
                    GraphDiscrepancy(
                        GraphDiscrepancyKind.STALE,
                        INSTRUMENT_FLOW_AGGREGATE,
                        flow.node.octoId,
                        "instrument-flow-of missing ${broken.joinToString("+")}",
                    )
            }
        }
        val accounted = expected.keys + forked.keys + awaiting
        actual.values.filter { it.octoId !in accounted }.forEach {
            discrepancies += GraphDiscrepancy(GraphDiscrepancyKind.ORPHAN, "unknown", it.octoId, "labels ${it.labels}")
        }
        return GraphReconciliation(tenantId, expected.size + forked.size + instrumentIds.size, discrepancies)
    }

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

    /** A promoted flow as its source row says it should look, with the endpoints its edges need. */
    private data class ExpectedFlow(
        val node: ExpectedNode,
        val instrumentId: UUID,
        val wallet: ExpectedNode,
        val walletAddress: String,
    )

    /**
     * The tenant's promoted `instrument_flow` rows (#565). Tenant derivation mirrors the RLS
     * policy — the wallet's `tracked_address` row — stated explicitly so the read does not
     * depend on policy internals. A chain [walletSelector] cannot map is skipped: its outbox row
     * already failed, and that is the discrepancy that reports it.
     */
    private fun expectedInstrumentFlows(
        tenantId: UUID,
        scope: TenantScope,
    ): Map<UUID, ExpectedFlow> {
        val sql =
            """
            select f.id, f.external_id, f.flow_type, f.amount_raw, f.decimals,
                   f.occurred_at, f.recorded_at, f.slot, f.signature, f.token_account,
                   f.instrument_id, f.chain, f.wallet
              from octo.instrument_flow f
              join octo.tracked_address t on t.chain = f.chain and t.address = f.wallet
             where t.tenant_id = ?
             order by f.recorded_at, f.id
            """.trimIndent()
        return dataSource
            .scoped(scope) { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                val props =
                                    flowGraphProperties(
                                        rows.getString(2),
                                        rows.getString(3),
                                        rows.getBigDecimal(4).toPlainString(),
                                        rows.getInt(5),
                                        rows.getObject(6, OffsetDateTime::class.java).toInstant(),
                                        rows.getObject(7, OffsetDateTime::class.java).toInstant(),
                                        rows.getLong(8).takeIf { !rows.wasNull() },
                                        rows.getString(9),
                                        rows.getString(10),
                                    )
                                val instrumentId = rows.getObject(11, UUID::class.java)
                                val chain = rows.getString(12)
                                val address = rows.getString(13)
                                val selector = runCatching { walletSelector(chain) }.getOrNull() ?: continue
                                val flow =
                                    ExpectedNode(
                                        rows.getObject(1, UUID::class.java),
                                        INSTRUMENT_FLOW_AGGREGATE,
                                        setOf("InstrumentFlow"),
                                        props + ("tenantId" to tenantId.toString()),
                                    )
                                val wallet =
                                    ExpectedNode(
                                        walletOctoId(tenantId, chain, address),
                                        "wallet",
                                        setOf(selector.first.removePrefix(":")),
                                        mapOf(selector.second to address, "tenantId" to tenantId.toString()),
                                    )
                                add(flow.octoId to ExpectedFlow(flow, instrumentId, wallet, address))
                            }
                        }
                    }
                }
            }.toMap()
    }

    /** The ids in [instrumentIds] that have no `:Instrument` node — global reference data, checked once per tenant that needs it. */
    private fun absentInstruments(instrumentIds: Set<UUID>): List<UUID> {
        if (instrumentIds.isEmpty()) return emptyList()
        return driver
            .executableQuery(
                "UNWIND \$ids AS iid OPTIONAL MATCH (i:Instrument {instrumentId: iid}) RETURN iid AS id, i IS NULL AS absent",
            ).withParameters(mapOf("ids" to instrumentIds.map { it.toString() }))
            .withConfig(config)
            .execute()
            .records()
            .filter { it["absent"].asBoolean() }
            .map { UUID.fromString(it["id"].asString()) }
    }

    /** What one expected flow's relation wiring actually looks like in the graph. */
    private data class Wiring(
        val related: Boolean,
        val instrumentId: String?,
        val walletAddress: String?,
        val walletLabels: Set<String>,
    )

    /** For each expected flow: does an `:InstrumentFlowOf` relate it to the right instrument and wallet? */
    private fun flowWiring(
        tenantId: UUID,
        flowIds: List<UUID>,
    ): Map<UUID, Wiring> {
        if (flowIds.isEmpty()) return emptyMap()
        return driver
            .executableQuery(
                """
                UNWIND ${'$'}flowIds AS flowId
                OPTIONAL MATCH (f:InstrumentFlow {octoId: flowId, tenantId: ${'$'}tenantId})
                OPTIONAL MATCH (r:InstrumentFlowOf)-[:INSTRUMENT_FLOW_OF__FLOW_SIDE]->(f)
                OPTIONAL MATCH (r)-[:INSTRUMENT_FLOW_OF__INSTRUMENT_SIDE]->(i:Instrument)
                OPTIONAL MATCH (r)-[:INSTRUMENT_FLOW_OF__WALLET_SIDE]->(w)
                RETURN flowId AS flowId, r IS NOT NULL AS related,
                       i.instrumentId AS instrumentId,
                       coalesce(w.solanaAddress, w.evmAddress) AS walletAddress,
                       labels(w) AS walletLabels
                """.trimIndent(),
            ).withParameters(mapOf("flowIds" to flowIds.map { it.toString() }, "tenantId" to tenantId.toString()))
            .withConfig(config)
            .execute()
            .records()
            .associate { record ->
                UUID.fromString(record["flowId"].asString()) to
                    Wiring(
                        record["related"].asBoolean(),
                        record["instrumentId"].takeIf { !it.isNull }?.asString(),
                        record["walletAddress"].takeIf { !it.isNull }?.asString(),
                        record["walletLabels"].takeIf { !it.isNull }?.asList { it.asString() }?.toSet() ?: emptySet(),
                    )
            }
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
    )

    /** Every node this tenant owns in the graph, keyed by octoId. */
    private fun graphNodes(tenantId: UUID): Map<UUID, ActualNode> =
        driver
            .executableQuery(
                "MATCH (n) WHERE n.tenantId = \$tenantId AND n.octoId IS NOT NULL RETURN n.octoId AS id, labels(n) AS labels, properties(n) AS props",
            ).withParameters(mapOf("tenantId" to tenantId.toString()))
            .withConfig(config)
            .execute()
            .records()
            .associate { record ->
                val id = UUID.fromString(record["id"].asString())
                id to
                    ActualNode(
                        id,
                        record["labels"].asList { it.asString() }.toSet(),
                        record["props"].asMap { it.asObject()?.toString().orEmpty() }.minus("octoId"),
                    )
            }
}
