package com.octo.iborcore

import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class InstrumentFlowProjectionTest {
    private val tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val otherTenant = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    private val instrumentId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    private val solanaWallet = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    private val evmWallet = "0x1111111111111111111111111111111111111111"
    private val usdc = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
    private val whenAt = Instant.parse("2026-02-01T09:30:00.123456789Z")

    private val native =
        ProjectedInstrument(instrumentId, "solana:native", "solana", null, "native-token", 9)

    private fun flow(
        chain: String = "solana",
        wallet: String = solanaWallet,
        instrument: UUID = instrumentId,
        amount: String = "500",
        slot: Long? = 284411003,
        supersedes: UUID? = null,
        id: UUID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
    ) = InstrumentFlow(
        id = id,
        externalId = "$chain:sig:$wallet:bal:0",
        instrumentId = instrument,
        chain = chain,
        wallet = wallet,
        tokenAccount = null,
        flowType = InstrumentFlowType.TRANSFER_IN,
        amountRaw = BigInteger(amount),
        decimals = 9,
        occurredAt = whenAt,
        recordedAt = whenAt,
        slot = slot,
        signature = "sig",
        supersedesId = supersedes,
        rationale = supersedes?.let { "restated" },
    )

    @Test
    fun `a solana flow enqueues the instrument, wallet, flow, and relation in that order`() {
        val specs = instrumentFlowProjection(tenant, flow(), flow().id, native)

        assertEquals(
            listOf(GRAPH_INSTRUMENT, GRAPH_WALLET, GRAPH_INSTRUMENT_FLOW, GRAPH_INSTRUMENT_FLOW_OF),
            specs.map { it.aggregateType },
        )
        assertEquals("native-token", specs[0].kind)
        assertEquals(instrumentId, specs[0].aggregateId)
        assertEquals("solana:native", specs[0].properties["instrumentId"])
        assertEquals("9", specs[0].properties["decimals"])
        assertNull(specs[0].properties["solanaAddress"])

        assertEquals("solana", specs[1].kind)
        assertEquals(walletGraphId(tenant, "solana", solanaWallet), specs[1].aggregateId)
        assertEquals(solanaWallet, specs[1].properties["solanaAddress"])
        assertNotEquals(specs[1].aggregateId, walletGraphId(otherTenant, "solana", solanaWallet))

        assertEquals("500", specs[2].properties["monetaryAmount"])
        assertEquals("2026-02-01T09:30:00.123Z", specs[2].properties["occurredAt"])
        assertEquals("284411003", specs[2].properties["slot"])
        assertEquals(flow().id, specs[2].aggregateId)

        assertEquals(instrumentFlowOfGraphId(flow().id), specs[3].aggregateId)
        assertEquals(INSTRUMENT_FLOW_OF_EDGES, specs[3].endpoints.keys.toList())
        assertEquals(flow().id, specs[3].endpoints["FLOW_SIDE"])
        assertEquals(instrumentId, specs[3].endpoints["INSTRUMENT_SIDE"])
        assertEquals(specs[1].aggregateId, specs[3].endpoints["WALLET_SIDE"])
        @Suppress("UNCHECKED_CAST")
        val endpoints = specs[3].payload()["endpoints"] as Map<String, String>
        assertEquals(flow().id.toString(), endpoints["FLOW_SIDE"])
    }

    @Test
    fun `a correction updates the lineage root and drops a null slot`() {
        val original = flow()
        val corrected =
            flow(
                amount = "150",
                slot = null,
                supersedes = original.id,
                id = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            )
        val specs = instrumentFlowProjection(tenant, corrected, original.id, native)

        assertEquals(original.id, specs[2].aggregateId)
        assertEquals("150", specs[2].properties["monetaryAmount"])
        assertNull(specs[2].properties["slot"])
        assertEquals(instrumentFlowOfGraphId(original.id), specs[3].aggregateId)
        assertEquals(original.id, specs[3].endpoints["FLOW_SIDE"])
    }

    @Test
    fun `an EVM erc20 flow projects an evm wallet and an evm contract`() {
        val contract =
            ProjectedInstrument(
                instrumentId,
                "arbitrum-one:contract:$usdc",
                "arbitrum-one",
                usdc,
                "erc20",
                6,
            )
        val specs = instrumentFlowProjection(tenant, flow(chain = "arbitrum-one", wallet = evmWallet), flow().id, contract)

        assertEquals("evm-contract", specs[0].kind)
        assertEquals(usdc, specs[0].properties["evmAddress"])
        assertEquals("erc20", specs[0].properties["instrumentKind"])
        assertEquals("evm", specs[1].kind)
        assertEquals(evmWallet, specs[1].properties["evmAddress"])
    }

    @Test
    fun `an unknown chain is refused`() {
        assertFailsWith<IllegalArgumentException> {
            instrumentFlowProjection(tenant, flow(chain = "ethereum", wallet = evmWallet), flow().id, native.copy(chain = "ethereum"))
        }
    }
}
