package com.octo.api.ingestion

import com.octo.iborcore.InstrumentFlow
import com.octo.iborcore.InstrumentFlowPromoter
import com.octo.iborcore.InstrumentFlowStore
import com.octo.iborcore.InstrumentKey
import com.octo.iborcore.StagedTransfer
import com.octo.persistence.TenantScope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

// #310: a pass promotes, a replay adds nothing, and a failed pass is counted instead of killing the scheduler.
class OnchainPromotionRunnerTest {
    private val native = UUID.randomUUID()

    private inner class Store : InstrumentFlowStore {
        val rows = mutableListOf<StagedTransfer>()
        val flows = mutableListOf<InstrumentFlow>()
        var down = false

        override fun unpromotedTransfers(): List<StagedTransfer> {
            check(!down) { "database down" }
            return rows.filter { r -> flows.none { it.externalId == r.externalId } }
        }

        override fun instrumentIds() = mapOf(InstrumentKey("solana", null) to native)

        override fun flowIdForStaging(stagingRowId: UUID): UUID? = null

        override fun insertFlow(
            flow: InstrumentFlow,
            sourceSystem: String,
            ingestionRunId: UUID,
            correlationId: UUID,
        ) = flows.none { it.externalId == flow.externalId }.also { if (it) flows += flow }

        override fun flowsFor(
            chain: String,
            wallet: String,
            scope: TenantScope,
        ) = flows.filter { it.chain == chain && it.wallet == wallet }
    }

    private fun staged(
        externalId: String,
        mint: String?,
    ) = StagedTransfer(
        id = UUID.randomUUID(),
        externalId = externalId,
        chain = "solana",
        signature = "sig",
        slot = 1,
        blockTime = Instant.now(),
        wallet = "W",
        tokenAccount = null,
        mintAddress = mint,
        amountRaw = BigInteger.ONE,
        decimals = 9,
        transferKind = "transfer-in",
        supersedesId = null,
        rationale = null,
        recordedAt = Instant.now(),
        sourceSystem = "helius-solana",
        ingestionRunId = UUID.randomUUID(),
        correlationId = UUID.randomUUID(),
    )

    @Test
    fun `promotes once, gauges quarantine, and survives a failed pass`() {
        val store = Store().apply { rows += listOf(staged("a", null), staged("q", "MINT")) }
        val meters = SimpleMeterRegistry()
        val runner = OnchainPromotionRunner(InstrumentFlowPromoter(store), meters)

        runner.poll()
        runner.poll()
        store.down = true
        runner.poll()

        assertThat(store.flows.map { it.externalId }).containsExactly("a")
        assertThat(meters.counter("onchain.promotion.promoted").count()).isEqualTo(1.0)
        assertThat(meters.get("onchain.promotion.quarantined").gauge().value()).isEqualTo(1.0)
        assertThat(meters.counter("onchain.promotion.errors").count()).isEqualTo(1.0)
    }
}
