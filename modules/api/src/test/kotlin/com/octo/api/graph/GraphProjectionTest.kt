package com.octo.api.graph

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The `instrument-flow` write's shape (#565): the chain picks the wallet label on the server
 * side, every endpoint is merged on its unique key, and a malformed payload is rejected instead
 * of written half-way.
 */
class GraphProjectionTest {
    private val tenantId = UUID.randomUUID()
    private val flowId = UUID.randomUUID()
    private val instrumentId = UUID.randomUUID()
    private val wallet = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZ9dusFVt7f"

    private fun row(
        chain: String = "solana",
        properties: Map<String, Any?> =
            mapOf(
                "externalId" to "sig:0:bal:0",
                "flowType" to "transfer-in",
                "amountRaw" to "42",
                "decimals" to "6",
                "occurredAt" to "2026-01-01T00:00:00Z",
                "recordedAt" to "2026-01-01T00:00:01Z",
                "slot" to "7",
            ),
        payload: Map<String, Any?> =
            mapOf(
                "chain" to chain,
                "properties" to properties,
                "walletAddress" to wallet,
                "instrumentId" to instrumentId.toString(),
                "instrument" to mapOf("chain" to chain, "mintAddress" to "mint-abc", "instrumentKind" to "spl-token"),
            ),
    ) = OutboxRow(1L, tenantId, "instrument-flow", flowId, ObjectMapper().writeValueAsString(payload), 0, UUID.randomUUID())

    @Test
    fun `a solana flow projects flow, wallet, instrument and the reified relation`() {
        val write = graphWrite(row())
        assertThat(write.cypher)
            .contains("MERGE (f:InstrumentFlow {octoId: \$octoId})")
            .contains("MERGE (w:Wallet {tenantId: \$tenantId, solanaAddress: \$walletAddress})")
            .contains("MERGE (r)-[:INSTRUMENT_FLOW_OF__FLOW_SIDE]->(f)")
            .contains("MERGE (r)-[:INSTRUMENT_FLOW_OF__INSTRUMENT_SIDE]->(i)")
            .contains("MERGE (r)-[:INSTRUMENT_FLOW_OF__WALLET_SIDE]->(w)")
        assertThat(write.parameters["octoId"]).isEqualTo(flowId.toString())
        assertThat(write.parameters["instrumentId"]).isEqualTo(instrumentId.toString())
        assertThat(write.parameters["walletOctoId"])
            .isEqualTo(walletOctoId(tenantId, "solana", wallet).toString())
        @Suppress("UNCHECKED_CAST")
        val props = write.parameters["properties"] as Map<String, String>
        assertThat(props).containsEntry("flowType", "transfer-in").containsEntry("amountRaw", "42")
    }

    @Test
    fun `an arbitrum flow projects an EvmWallet endpoint`() {
        val write = graphWrite(row(chain = "arbitrum-one"))
        assertThat(write.cypher).contains("MERGE (w:EvmWallet {tenantId: \$tenantId, evmAddress: \$walletAddress})")
        assertThat(write.parameters["walletOctoId"])
            .isEqualTo(walletOctoId(tenantId, "arbitrum-one", wallet).toString())
    }

    @Test
    fun `a payload missing required state or on an unmapped chain is refused`() {
        assertThatThrownBy { graphWrite(row(properties = mapOf("externalId" to "x"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { graphWrite(row(chain = "bitcoin")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no wallet projection")
    }
}
