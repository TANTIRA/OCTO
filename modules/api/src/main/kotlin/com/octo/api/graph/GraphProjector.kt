package com.octo.api.graph

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.iborcore.INSTRUMENT_FLOW_OF_EDGES
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.neo4j.driver.Driver
import org.neo4j.driver.QueryConfig
import org.neo4j.driver.exceptions.Neo4jException
import org.neo4j.driver.exceptions.ServiceUnavailableException
import org.neo4j.driver.exceptions.SessionExpiredException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** One Cypher write and its parameters, built from an outbox row. */
internal data class GraphWrite(
    val cypher: String,
    val parameters: Map<String, Any?>,
)

/**
 * The labels a projected kind carries (the full `sub` chain), the ontology properties it must have, and — for the
 * first relationship-bearing projection — the role edges it writes. Labels and relationship types cannot be Cypher
 * parameters, so both come only from this table.
 *
 * [mergeOn] is `octoId` for tenant nodes. Global instruments merge on `instrumentId`, the reference-data key, and
 * then store `octoId`. [tenantScoped] is false for that reference data: the node carries no `tenantId`.
 */
internal data class Projection(
    val labels: String,
    val properties: List<String>,
    val optionalProperties: List<String> = emptyList(),
    val edges: List<String> = emptyList(),
    val tenantScoped: Boolean = true,
    val mergeOn: String = "octoId",
) {
    val labelSet: Set<String> get() = labels.split(':').filter(String::isNotEmpty).toSet()
}

/**
 * Every node shape the projector may write, keyed by outbox `aggregate_type` and payload `kind`. A payload can
 * never inject a label or a relationship type.
 */
internal val PROJECTIONS =
    mapOf(
        ("asset" to "fund") to Projection(":Fund", listOf("legalName")),
        ("asset" to "investment") to Projection(":Investment", listOf("displayName")),
        ("asset" to "operating-company") to Projection(":OperatingCompany:Organization:Party", listOf("legalName")),
        ("instrument" to "native-token") to
            Projection(
                ":Instrument",
                listOf("instrumentId", "instrumentKind", "chainId", "decimals"),
                tenantScoped = false,
                mergeOn = "instrumentId",
            ),
        ("instrument" to "solana-mint") to
            Projection(
                ":SolanaMint:Instrument",
                listOf("instrumentId", "instrumentKind", "chainId", "decimals", "solanaAddress"),
                tenantScoped = false,
                mergeOn = "instrumentId",
            ),
        ("instrument" to "evm-contract") to
            Projection(
                ":EvmContract:Instrument",
                listOf("instrumentId", "instrumentKind", "chainId", "decimals", "evmAddress"),
                tenantScoped = false,
                mergeOn = "instrumentId",
            ),
        ("wallet" to "solana") to Projection(":Wallet", listOf("solanaAddress", "chainId")),
        ("wallet" to "evm") to Projection(":EvmWallet", listOf("evmAddress", "chainId")),
        ("instrument-flow" to "flow") to
            Projection(
                ":InstrumentFlow",
                listOf("instrumentFlowType", "monetaryAmount", "occurredAt", "recordedAt", "externalId"),
                optionalProperties = listOf("slot"),
            ),
        ("instrument-flow-of" to "relation") to
            Projection(":InstrumentFlowOf", emptyList(), edges = INSTRUMENT_FLOW_OF_EDGES),
    )

private val json = ObjectMapper()

private val LABELS = Regex("^(:[A-Z][A-Za-z0-9]*)+$")
private val PROPERTY = Regex("^[A-Za-z][A-Za-z0-9]*$")
private val RELATIONSHIP = Regex("^[A-Z][A-Z0-9_]*$")
private val UUID_TEXT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * The idempotent write for [row]. Tenant nodes `MERGE` on `octoId`; global instruments `MERGE` on `instrumentId`
 * and then store `octoId`. A relationship-bearing row matches its endpoints first — a missing endpoint fails the
 * write so the row retries — then replaces its role edges, so a correction retargets instead of accumulating.
 * Replaying a row leaves the newest state.
 */
internal fun graphWrite(row: OutboxRow): GraphWrite {
    val payload = json.readTree(row.payload)
    val kind = payload.path("kind").asText()
    val projection =
        requireNotNull(PROJECTIONS[row.aggregateType to kind]) { "no graph projection for ${row.aggregateType}/$kind" }
    require(projection.labels.matches(LABELS)) { "projection labels are not a label chain" }
    require(projection.mergeOn.matches(PROPERTY)) { "projection merge key is not a property name" }
    val properties = linkedMapOf<String, Any?>()
    for (name in projection.properties) {
        properties[name] =
            textProp(payload, name)
                ?: throw IllegalArgumentException("${row.aggregateType}/$kind payload is missing $name")
    }
    for (name in projection.optionalProperties) {
        textProp(payload, name)?.let { properties[name] = it }
    }
    val endpoints =
        projection.edges.map { edge ->
            require(edge.matches(RELATIONSHIP)) { "projection edge $edge is not a relationship type" }
            val id = payload.path("endpoints").path(edge).asText("")
            require(id.matches(UUID_TEXT)) { "${row.aggregateType}/$kind payload is missing endpoint $edge" }
            edge to id
        }
    val mergeKey =
        if (projection.mergeOn == "octoId") {
            null
        } else {
            properties[projection.mergeOn]?.toString()
                ?: throw IllegalArgumentException("${row.aggregateType}/$kind cannot merge on ${projection.mergeOn}")
        }
    val parameters =
        linkedMapOf<String, Any?>(
            "octoId" to row.aggregateId.toString(),
            "properties" to properties,
        )
    if (projection.tenantScoped) parameters["tenantId"] = row.tenantId.toString()
    if (mergeKey != null) parameters["mergeKey"] = mergeKey
    endpoints.forEachIndexed { index, (_, id) -> parameters["e$index"] = id }
    return GraphWrite(cypher(projection, endpoints, mergeKey != null), parameters)
}

private fun textProp(
    payload: JsonNode,
    name: String,
): String? =
    payload
        .path("properties")
        .path(name)
        .takeIf { it.isTextual && it.asText().isNotBlank() }
        ?.asText()

private fun cypher(
    projection: Projection,
    endpoints: List<Pair<String, String>>,
    mergesOnBusinessKey: Boolean,
): String =
    buildString {
        endpoints.forEachIndexed { index, _ -> append("MATCH (e$index {octoId: \$e$index}) ") }
        if (mergesOnBusinessKey) {
            append("MERGE (n${projection.labels} {${projection.mergeOn}: \$mergeKey}) ")
        } else {
            append("MERGE (n${projection.labels} {octoId: \$octoId}) ")
        }
        append("SET n += \$properties")
        if (projection.tenantScoped) append(", n.tenantId = \$tenantId")
        if (mergesOnBusinessKey) append(", n.octoId = \$octoId")
        append(' ')
        if (endpoints.isNotEmpty()) {
            val vars = endpoints.indices.joinToString(", ") { "e$it" }
            val types = endpoints.joinToString("|") { it.first }
            append("WITH n, $vars OPTIONAL MATCH (n)-[old:$types]->() DELETE old WITH DISTINCT n, $vars ")
            endpoints.forEachIndexed { index, (type, _) ->
                append("MERGE (n)-[r$index:$type]->(e$index) ")
                if (projection.tenantScoped) append("SET r$index.tenantId = \$tenantId ")
            }
        }
        append("RETURN n.octoId AS written")
    }

/**
 * Drains `octo.graph_outbox` into Neo4j (ADR-0004 amendment, #308). Each row is applied in its own write, then marked
 * applied; a row Neo4j rejects spends one attempt and backs off, and ends `failed` (counted, reported, never skipped)
 * once its budget is gone. When the graph is unreachable the claimed rows are handed back untouched — ledger writes
 * never depended on the graph, and the outbox simply waits.
 */
class GraphProjector(
    private val outbox: JdbcGraphOutboxStore,
    private val driver: Driver,
    database: String,
    meters: MeterRegistry?,
    private val batchSize: Int = 100,
    private val retention: Duration = Duration.ofDays(30),
) {
    private val log = LoggerFactory.getLogger(GraphProjector::class.java)
    private val config = QueryConfig.builder().withDatabase(database).build()
    private val stats = AtomicReference(OutboxStats(0, 0, 0.0))
    private val appliedRows = meters?.let { Counter.builder("graph.outbox.applied").register(it) }
    private val failedAttempts = meters?.let { Counter.builder("graph.outbox.attempt_failures").register(it) }
    private val unreachable = meters?.let { Counter.builder("graph.outbox.graph_unreachable").register(it) }

    init {
        if (meters != null) {
            Gauge.builder("graph.outbox.pending") { stats.get().pending.toDouble() }.register(meters)
            Gauge.builder("graph.outbox.failed") { stats.get().failed.toDouble() }.register(meters)
            Gauge.builder("graph.outbox.lag_seconds") { stats.get().oldestPendingSeconds }.register(meters)
        }
    }

    @Scheduled(
        fixedDelayString = "\${GRAPH_PROJECTOR_POLL_MS:5000}",
        initialDelayString = "\${GRAPH_PROJECTOR_INITIAL_DELAY_MS:10000}",
    )
    fun poll() {
        try {
            drain()
            outbox.prune(retention)
            stats.set(outbox.stats())
        } catch (e: Exception) {
            log.warn("graph projector poll failed: {}", describe(e))
        }
    }

    /**
     * Applies due rows until none can be claimed — a node's next upsert only becomes claimable once its earlier one
     * is applied, so draining loops rather than stopping after one batch. Returns how many landed; stops early, handing
     * rows back, if the graph is unreachable.
     */
    fun drain(): Int {
        var applied = 0
        while (true) {
            val rows = outbox.claim(batchSize)
            if (rows.isEmpty()) return applied
            for ((index, row) in rows.withIndex()) {
                try {
                    val write = graphWrite(row)
                    val written =
                        driver
                            .executableQuery(write.cypher)
                            .withParameters(write.parameters)
                            .withConfig(config)
                            .execute()
                    if (written.records().isEmpty()) throw IllegalStateException("graph write matched nothing")
                    outbox.applied(row)
                    applied++
                    appliedRows?.increment()
                } catch (e: Exception) {
                    if (e is ServiceUnavailableException || e is SessionExpiredException) {
                        rows.drop(index).forEach(outbox::release)
                        unreachable?.increment()
                        log.warn("graph unreachable, {} outbox rows handed back: {}", rows.size - index, describe(e))
                        return applied
                    }
                    outbox.failed(row, describe(e))
                    failedAttempts?.increment()
                    log.warn("graph outbox row {} ({}/{}) failed: {}", row.seq, row.aggregateType, row.aggregateId, describe(e))
                }
            }
        }
    }

    // Neo4j codes and exception types only: messages can quote node property values (legal names, addresses).
    private fun describe(e: Exception) = "${e.javaClass.simpleName}: ${(e as? Neo4jException)?.code() ?: e.message}"
}
