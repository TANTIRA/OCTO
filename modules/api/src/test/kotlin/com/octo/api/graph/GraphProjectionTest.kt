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

/**
 * Promoted instrument flows project a tenant wallet whose label comes from the chain (#565).
 * Solana is `:Wallet`; Arbitrum is `:EvmWallet`. An unmapped chain is refused before a write.
 */
class GraphProjectionTest {
    private val json = ObjectMapper()
    private val tenantId = UUID.randomUUID()
    private val instrumentId = UUID.randomUUID()
    private val wallet = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZ9dusFVt7f"

    @Test
    fun `a solana flow projects a tenant Wallet`() {
        val specs = projection("solana", "spl-token")
        val walletSpec = specs.first { it.aggregateType == "wallet" }
        val write = graphWrite(row(walletSpec))

        assertThat(write.cypher).contains("MERGE (n:Wallet {octoId: \$octoId})")
        assertThat(write.cypher).contains("n.tenantId = \$tenantId")
        assertThat(walletSpec.properties).containsEntry("solanaAddress", wallet)
    }

    @Test
    fun `an arbitrum flow projects an EvmWallet`() {
        val specs = projection("arbitrum-one", "erc-20")
        val walletSpec = specs.first { it.aggregateType == "wallet" }
        val write = graphWrite(row(walletSpec))

        assertThat(write.cypher).contains("MERGE (n:EvmWallet {octoId: \$octoId})")
        assertThat(walletSpec.properties).containsEntry("evmAddress", wallet)
    }

    @Test
    fun `an unmapped chain is refused before a write`() {
        assertThatThrownBy { projection("bitcoin", "native-token") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no wallet projection")
    }

    private fun projection(
        chain: String,
        kind: String,
    ) = instrumentFlowProjection(
        tenantId,
        InstrumentFlow(
            id = UUID.randomUUID(),
            externalId = "$chain:sig:$wallet:tok:0",
            instrumentId = instrumentId,
            chain = chain,
            wallet = wallet,
            tokenAccount = null,
            flowType = InstrumentFlowType.TRANSFER_IN,
            amountRaw = BigInteger("42"),
            decimals = 6,
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            recordedAt = Instant.parse("2026-01-01T00:00:01Z"),
            slot = 7,
            signature = "sig",
        ),
        UUID.randomUUID(),
        ProjectedInstrument(instrumentId, "$chain:mint:abc", chain, "mint-abc", kind, 6),
    )

    private fun row(spec: com.octo.iborcore.GraphNodeSpec) =
        OutboxRow(1L, tenantId, spec.aggregateType, spec.aggregateId, json.writeValueAsString(spec.payload()), 0, UUID.randomUUID())
}
