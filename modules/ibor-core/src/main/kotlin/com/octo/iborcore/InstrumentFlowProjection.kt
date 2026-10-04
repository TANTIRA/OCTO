package com.octo.iborcore

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Outbox aggregate for a global `:Instrument`. Merged on `instrumentId`, not scoped to a tenant. */
const val GRAPH_INSTRUMENT = "instrument"

/** Outbox aggregate for a tenant's `:Wallet` or `:EvmWallet`. */
const val GRAPH_WALLET = "wallet"

/** Outbox aggregate for one `:InstrumentFlow` lineage. */
const val GRAPH_INSTRUMENT_FLOW = "instrument-flow"

/** Outbox aggregate for the reified `:InstrumentFlowOf` node and its three edges. */
const val GRAPH_INSTRUMENT_FLOW_OF = "instrument-flow-of"

/**
 * Role edges of `instrument-flow-of`, declaration order. The projector interpolates these names into Cypher;
 * a payload cannot add a relationship type.
 */
val INSTRUMENT_FLOW_OF_EDGES = listOf("FLOW_SIDE", "INSTRUMENT_SIDE", "WALLET_SIDE")

/**
 * One graph upsert the promotion transaction enqueues. [aggregateId] is the node's `octoId`. [endpoints] names
 * each role edge and the `octoId` it points at; empty for a node that has no edges.
 */
data class GraphNodeSpec(
    val aggregateType: String,
    val aggregateId: UUID,
    val kind: String,
    val properties: Map<String, String>,
    val endpoints: Map<String, UUID> = emptyMap(),
) {
    /** Outbox payload. Labels stay out of it — the projector derives them from [aggregateType] and [kind]. */
    fun payload(): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>("kind" to kind, "properties" to properties)
        if (endpoints.isNotEmpty()) body["endpoints"] = endpoints.mapValues { it.value.toString() }
        return body
    }
}

/** Registry row the projection needs. [externalKey] is the ontology `instrumentId`. */
data class ProjectedInstrument(
    val id: UUID,
    val externalKey: String,
    val chain: String,
    val mintAddress: String?,
    val kind: String,
    val decimals: Int,
)

/**
 * Graph identity of a wallet. `tracked_address` is keyed by `(chain, address)` and has no row uuid, so the
 * projected node's octoId is the name-based UUID of the tenant and that key.
 */
fun walletGraphId(
    tenantId: UUID,
    chain: String,
    address: String,
): UUID = UUID.nameUUIDFromBytes("octo:wallet:$tenantId:$chain:$address".toByteArray(StandardCharsets.UTF_8))

/** Graph identity of the reified relation for a flow lineage. Distinct from the flow node's octoId. */
fun instrumentFlowOfGraphId(flowRootId: UUID): UUID =
    UUID.nameUUIDFromBytes("octo:instrument-flow-of:$flowRootId".toByteArray(StandardCharsets.UTF_8))

/** Payload `kind` for a wallet: `solana` (`:Wallet`) or `evm` (`:EvmWallet`). */
fun walletProjectionKind(chain: String): String =
    when {
        chain == "solana" -> "solana"
        chain.startsWith("arbitrum-") -> "evm"
        else -> throw IllegalArgumentException("no wallet projection for chain $chain")
    }

/**
 * Payload `kind` for an instrument. Native assets stay `:Instrument`. Anything with an address takes the
 * chain's subtype (`:SolanaMint` or `:EvmContract`); [ProjectedInstrument.kind] is still stored as `instrumentKind`.
 */
fun instrumentProjectionKind(
    kind: String,
    chain: String,
): String =
    when {
        kind == "native-token" -> "native-token"
        chain == "solana" -> "solana-mint"
        chain.startsWith("arbitrum-") -> "evm-contract"
        else -> throw IllegalArgumentException("no instrument projection for $kind on $chain")
    }

/**
 * The four upserts a promoted flow contributes, endpoint nodes first so a projector that applies rows in
 * `seq` order writes the targets before the edges. [rootId] is the lineage root: a correction updates that
 * flow node and its relation instead of creating a second pair. [flow] is the current head, so the properties
 * are the latest state.
 */
fun instrumentFlowProjection(
    tenantId: UUID,
    flow: InstrumentFlow,
    rootId: UUID,
    instrument: ProjectedInstrument,
): List<GraphNodeSpec> {
    require(flow.chain == instrument.chain) {
        "flow ${flow.id} is on ${flow.chain} but instrument ${instrument.id} is on ${instrument.chain}"
    }
    val instrumentKind = instrumentProjectionKind(instrument.kind, instrument.chain)
    val walletKind = walletProjectionKind(flow.chain)
    val walletId = walletGraphId(tenantId, flow.chain, flow.wallet)
    return listOf(
        GraphNodeSpec(GRAPH_INSTRUMENT, instrument.id, instrumentKind, instrumentProperties(instrument, instrumentKind)),
        GraphNodeSpec(GRAPH_WALLET, walletId, walletKind, walletProperties(flow.chain, flow.wallet, walletKind)),
        GraphNodeSpec(GRAPH_INSTRUMENT_FLOW, rootId, "flow", flowProperties(flow)),
        GraphNodeSpec(
            GRAPH_INSTRUMENT_FLOW_OF,
            instrumentFlowOfGraphId(rootId),
            "relation",
            emptyMap(),
            linkedMapOf(
                "FLOW_SIDE" to rootId,
                "INSTRUMENT_SIDE" to instrument.id,
                "WALLET_SIDE" to walletId,
            ),
        ),
    )
}

private fun instrumentProperties(
    instrument: ProjectedInstrument,
    projectionKind: String,
): Map<String, String> {
    val properties =
        linkedMapOf(
            "instrumentId" to instrument.externalKey,
            "instrumentKind" to instrument.kind,
            "chainId" to instrument.chain,
            "decimals" to instrument.decimals.toString(),
        )
    if (projectionKind == "native-token") return properties
    val address =
        instrument.mintAddress?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("instrument ${instrument.id} ($projectionKind) has no address")
    properties[if (projectionKind == "solana-mint") "solanaAddress" else "evmAddress"] = address
    return properties
}

private fun walletProperties(
    chain: String,
    address: String,
    projectionKind: String,
): Map<String, String> =
    when (projectionKind) {
        "solana" -> linkedMapOf("solanaAddress" to address, "chainId" to chain)
        else -> linkedMapOf("evmAddress" to address, "chainId" to chain)
    }

private fun flowProperties(flow: InstrumentFlow): Map<String, String> {
    val properties =
        linkedMapOf(
            "instrumentFlowType" to flow.flowType.wireValue,
            "monetaryAmount" to flow.amountRaw.toString(),
            "occurredAt" to graphInstant(flow.occurredAt),
            "recordedAt" to graphInstant(flow.recordedAt),
            "externalId" to flow.externalId,
        )
    flow.slot?.let { properties["slot"] = it.toString() }
    return properties
}

/** Millisecond precision, matching `timestamp` columns the promoter writes. */
private fun graphInstant(instant: Instant): String = instant.truncatedTo(ChronoUnit.MILLIS).toString()
