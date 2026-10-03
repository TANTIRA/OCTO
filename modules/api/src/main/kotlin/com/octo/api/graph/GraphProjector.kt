package com.octo.api.graph

import com.fasterxml.jackson.databind.ObjectMapper
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

/** The labels a projected kind carries (the full `sub` chain) and the ontology properties it must have. */
internal data class Projection(
    val labels: String,
    val properties: List<String>,
) {
    val labelSet: Set<String> get() = labels.split(':').filter(String::isNotEmpty).toSet()
}

/**
 * Every node shape the projector may write, keyed by outbox `aggregate_type` and payload `kind`. Labels cannot be
 * Cypher parameters, so they come only from this table — a payload can never inject a label.
 */
internal val PROJECTIONS =
    mapOf(
        ("asset" to "fund") to Projection(":Fund", listOf("legalName")),
        ("asset" to "investment") to Projection(":Investment", listOf("displayName")),
        ("asset" to "operating-company") to Projection(":OperatingCompany:Organization:Party", listOf("legalName")),
    )

private val json = ObjectMapper()

/**
 * The idempotent write for [row]: `MERGE` on the globally unique `octoId`, then set the node's tenant and its full
 * projected state. Replaying a row, or applying an older and a newer upsert of one node, leaves the newest state.
 */
internal fun graphWrite(row: OutboxRow): GraphWrite {
    if (row.aggregateType == INSTRUMENT_FLOW_AGGREGATE) return instrumentFlowGraphWrite(row)
    val payload = json.readTree(row.payload)
    val kind = payload.path("kind").asText()
    val projection =
        requireNotNull(PROJECTIONS[row.aggregateType to kind]) { "no graph projection for ${row.aggregateType}/$kind" }
    val properties =
        projection.properties.associateWith { name ->
            payload
                .path("properties")
                .path(name)
                .takeIf { it.isTextual && it.asText().isNotBlank() }
                ?.asText() ?: throw IllegalArgumentException("${row.aggregateType}/$kind payload is missing $name")
        }
    return GraphWrite(
        "MERGE (n${projection.labels} {octoId: \$octoId}) SET n += \$properties, n.tenantId = \$tenantId",
        mapOf("octoId" to row.aggregateId.toString(), "tenantId" to row.tenantId.toString(), "properties" to properties),
    )
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
                    driver
                        .executableQuery(write.cypher)
                        .withParameters(write.parameters)
                        .withConfig(config)
                        .execute()
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
