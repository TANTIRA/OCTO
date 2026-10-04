package com.octo.api.graph

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.iborcore.InstrumentFlow
import com.octo.iborcore.InstrumentFlowType
import com.octo.iborcore.ProjectedInstrument
import com.octo.iborcore.instrumentFlowProjection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** The Cypher the projector will run, without a database. Labels and relationship types stay in the projection table. */
class GraphWriteTest {
    private val json = ObjectMapper()
    private val tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val instrumentId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    private val wallet = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    private val mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    private val specs by lazy {
        val flow =
            InstrumentFlow(
                id = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                externalId = "solana:sig:$wallet:tok:0",
                instrumentId = instrumentId,
                chain = "solana",
                wallet = wallet,
                tokenAccount = null,
                flowType = InstrumentFlowType.TRANSFER_IN,
                amountRaw = BigInteger("250000000"),
                decimals = 6,
                occurredAt = Instant.parse("2026-02-01T09:30:00Z"),
                recordedAt = Instant.parse("2026-02-01T09:30:41Z"),
                slot = 10,
                signature = "sig",
            )
        val instrument = ProjectedInstrument(instrumentId, "solana:mint:$mint", "solana", mint, "spl-token", 6)
        instrumentFlowProjection(tenant, flow, flow.id, instrument)
    }

    @Test
    fun `every promoted shape is one the projector knows, and a payload cannot add an edge`() {
        for (spec in specs) {
            assertThat(PROJECTIONS).containsKey(spec.aggregateType to spec.kind)
            val projection = PROJECTIONS.getValue(spec.aggregateType to spec.kind)
            assertThat(spec.properties.keys).containsAll(projection.properties)
            if (projection.edges.isNotEmpty()) assertThat(spec.endpoints.keys).containsExactlyElementsOf(projection.edges)
        }

        val relation = specs.last()
        val payload = relation.payload().toMutableMap()

        @Suppress("UNCHECKED_CAST")
        val endpoints = (payload.getValue("endpoints") as Map<String, String>).toMutableMap()
        endpoints["HACKED"] = UUID.randomUUID().toString()
        payload["endpoints"] = endpoints
        val cypher = graphWrite(row(relation, json.writeValueAsString(payload))).cypher

        assertThat(cypher).contains("MERGE (n:InstrumentFlowOf {octoId: \$octoId})")
        assertThat(cypher).contains("MERGE (n)-[r0:FLOW_SIDE]->(e0)")
        assertThat(cypher).contains("MERGE (n)-[r1:INSTRUMENT_SIDE]->(e1)")
        assertThat(cypher).contains("MERGE (n)-[r2:WALLET_SIDE]->(e2)")
        assertThat(cypher).contains("DELETE old")
        assertThat(cypher).doesNotContain("HACKED")
    }

    @Test
    fun `an instrument merges on its global key and carries no tenant`() {
        val write = graphWrite(row(specs.first(), json.writeValueAsString(specs.first().payload())))

        assertThat(write.cypher).contains("MERGE (n:SolanaMint:Instrument {instrumentId: \$mergeKey})")
        assertThat(write.cypher).contains("n.octoId = \$octoId")
        assertThat(write.cypher).doesNotContain("tenantId")
        assertThat(write.parameters["mergeKey"]).isEqualTo("solana:mint:$mint")
        assertThat(write.parameters["octoId"]).isEqualTo(instrumentId.toString())
    }

    @Test
    fun `a wallet stays tenant scoped`() {
        val walletSpec = specs[1]
        val cypher = graphWrite(row(walletSpec, json.writeValueAsString(walletSpec.payload()))).cypher

        assertThat(cypher).contains("MERGE (n:Wallet {octoId: \$octoId})")
        assertThat(cypher).contains("n.tenantId = \$tenantId")
    }

    @Test
    fun `a relation without an endpoint is refused before any Cypher runs`() {
        val relation = specs.last()
        val payload = relation.payload().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        payload["endpoints"] = (payload.getValue("endpoints") as Map<String, String>).minus("FLOW_SIDE")

        assertThatThrownBy { graphWrite(row(relation, json.writeValueAsString(payload))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("FLOW_SIDE")
    }

    private fun row(
        spec: com.octo.iborcore.GraphNodeSpec,
        payload: String,
    ) = OutboxRow(1, tenant, spec.aggregateType, spec.aggregateId, payload, 0, UUID.randomUUID())
}
