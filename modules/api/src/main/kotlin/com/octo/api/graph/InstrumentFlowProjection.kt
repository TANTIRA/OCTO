package com.octo.api.graph

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.iborcore.InstrumentFlow
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * The instrument-flow projection (#565): the first outbox row that carries more than one node.
 * `octo.instrument_flow` is append-only, so every projected property is immutable — the upsert
 * is the whole state, and replay is a no-op.
 *
 * One promotion projects the flow's whole `instrument-flow-of` relation (ontology 2.0.0, the
 * reified `:InstrumentFlowOf` node): the `:InstrumentFlow` entity the tenant owns, the
 * `:Instrument` endpoint (global reference data — keyed by `instrumentId`, never tenant-scoped),
 * and the tenant-keyed wallet endpoint (`:Wallet` for `solana`, `:EvmWallet` for `arbitrum-*`),
 * then the relation node and its three role edges. Labels and edge types come only from
 * [walletSelector]/constants here — a payload can never inject a label.
 */
internal const val INSTRUMENT_FLOW_AGGREGATE = "instrument-flow"

/** Properties carried on `:InstrumentFlow` — all strings so expected-vs-actual compares stay textual. */
private val REQUIRED_FLOW_PROPS = listOf("externalId", "flowType", "amountRaw", "decimals", "occurredAt", "recordedAt")
private val OPTIONAL_FLOW_PROPS = listOf("slot", "signature", "tokenAccount")

/** The wallet node's label and tenant-keyed address property for [chain]; anything else is a projection bug. */
internal fun walletSelector(chain: String): Pair<String, String> =
    when {
        chain == "solana" -> ":Wallet" to "solanaAddress"
        chain.startsWith("arbitrum-") -> ":EvmWallet" to "evmAddress"
        else -> throw IllegalArgumentException("no wallet projection for chain $chain")
    }

/**
 * A wallet node's deterministic `octoId`: derived from the tenant-keyed address, so the
 * projection is idempotent without a Postgres-side wallet id (`tracked_address` is keyed by
 * `(chain, address)`) and the reconciler can expect exactly this node.
 */
internal fun walletOctoId(
    tenantId: UUID,
    chain: String,
    address: String,
): UUID = UUID.nameUUIDFromBytes("octo|wallet|$tenantId|$chain|$address".toByteArray(Charsets.UTF_8))

/** The `:InstrumentFlow` property set — shared by the outbox payload and the reconciler's expected nodes. */
internal fun flowGraphProperties(
    externalId: String,
    flowType: String,
    amountRaw: String,
    decimals: Int,
    occurredAt: Instant,
    recordedAt: Instant,
    slot: Long?,
    signature: String?,
    tokenAccount: String?,
): Map<String, String> =
    buildMap {
        put("externalId", externalId)
        put("flowType", flowType)
        put("amountRaw", amountRaw)
        put("decimals", decimals.toString())
        put("occurredAt", occurredAt.toString())
        put("recordedAt", recordedAt.toString())
        slot?.let { put("slot", it.toString()) }
        signature?.let { put("signature", it) }
        tokenAccount?.let { put("tokenAccount", it) }
    }

/**
 * Enqueues the flow's graph upsert on the promotion connection — the insert and the intent to
 * project it commit or roll back together (ADR-0004 amendment). The tenant is resolved the same
 * way RLS derives the flow's visibility: through `tracked_address`; an unwatched wallet owns no
 * tenant-visible flow, so nothing projects.
 */
fun enqueueInstrumentFlowProjection(
    connection: Connection,
    flow: InstrumentFlow,
) {
    val tenantId =
        connection
            .prepareStatement("select tenant_id from octo.tracked_address where chain = ? and address = ?")
            .use { statement ->
                statement.setString(1, flow.chain)
                statement.setString(2, flow.wallet)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
            } ?: return
    val instrument =
        connection
            .prepareStatement("select chain, mint_address, symbol, instrument_kind, external_key from octo.instrument where id = ?")
            .use { statement ->
                statement.setObject(1, flow.instrumentId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        mapOf(
                            "chain" to rows.getString(1),
                            "mintAddress" to rows.getString(2),
                            "symbol" to rows.getString(3),
                            "instrumentKind" to rows.getString(4),
                            "externalKey" to rows.getString(5),
                        ).filterValues { it != null }
                    } else {
                        emptyMap()
                    }
                }
            }
    enqueueGraphUpsert(
        connection,
        tenantId,
        INSTRUMENT_FLOW_AGGREGATE,
        flow.id,
        mapOf(
            "chain" to flow.chain,
            "properties" to
                flowGraphProperties(
                    flow.externalId,
                    flow.flowType.wireValue,
                    flow.amountRaw.toString(),
                    flow.decimals,
                    flow.occurredAt,
                    flow.recordedAt,
                    flow.slot,
                    flow.signature,
                    flow.tokenAccount,
                ),
            "walletAddress" to flow.wallet,
            "instrumentId" to flow.instrumentId.toString(),
            "instrument" to instrument,
        ),
    )
}

private fun JsonNode.requiredText(
    what: String,
    name: String,
): String =
    path(name).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
        ?: throw IllegalArgumentException("$what payload is missing $name")

/**
 * The idempotent write for one `instrument-flow` outbox row: the flow node, both endpoints, and
 * the reified relation with its three role edges, all `MERGE`d in one statement (#565). Every
 * edge MERGE is keyed on nodes that already exist in the same statement, so replay can never
 * duplicate a role.
 */
private val payloadJson = ObjectMapper()

internal fun instrumentFlowGraphWrite(row: OutboxRow): GraphWrite {
    val payload = payloadJson.readTree(row.payload)
    val chain = payload.requiredText(row.aggregateType, "chain")
    val (walletLabel, walletKey) = walletSelector(chain)
    val properties = payload.path("properties")
    val flowProps =
        REQUIRED_FLOW_PROPS.associateWith { properties.requiredText(row.aggregateType, it) } +
            OPTIONAL_FLOW_PROPS.mapNotNull { name ->
                properties.path(name).takeIf { it.isTextual && it.asText().isNotBlank() }?.let { name to it.asText() }
            }
    val instrumentProps =
        payload
            .path("instrument")
            .properties()
            .mapNotNull { (name, value) -> value.takeIf { it.isTextual }?.let { name to it.asText() } }
            .toMap()
    return GraphWrite(
        """
        MERGE (f:InstrumentFlow {octoId: ${'$'}octoId}) SET f += ${'$'}properties, f.tenantId = ${'$'}tenantId
        MERGE (i:Instrument {instrumentId: ${'$'}instrumentId}) SET i += ${'$'}instrument
        MERGE (w$walletLabel {tenantId: ${'$'}tenantId, $walletKey: ${'$'}walletAddress}) SET w.octoId = ${'$'}walletOctoId
        MERGE (r:InstrumentFlowOf {flowOctoId: ${'$'}octoId}) SET r.tenantId = ${'$'}tenantId
        MERGE (r)-[:INSTRUMENT_FLOW_OF__FLOW_SIDE]->(f)
        MERGE (r)-[:INSTRUMENT_FLOW_OF__INSTRUMENT_SIDE]->(i)
        MERGE (r)-[:INSTRUMENT_FLOW_OF__WALLET_SIDE]->(w)
        """.trimIndent(),
        mapOf(
            "octoId" to row.aggregateId.toString(),
            "tenantId" to row.tenantId.toString(),
            "properties" to flowProps,
            "instrumentId" to payload.requiredText(row.aggregateType, "instrumentId"),
            "instrument" to instrumentProps,
            "walletAddress" to payload.requiredText(row.aggregateType, "walletAddress"),
            "walletOctoId" to walletOctoId(row.tenantId, chain, payload.requiredText(row.aggregateType, "walletAddress")).toString(),
        ),
    )
}
