package com.mesta.asset.iborcore

import java.io.File
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val SOL = UUID.randomUUID()
private val BONK = UUID.randomUUID()
private const val WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
private const val OTHER_WALLET = "9gWfZ5xJ9kN8vB6tY3rQ1mP4sD7fH2jL8uK5iE3wA6bN"
private val NOW = Instant.parse("2025-06-01T00:00:00Z")

private fun flow(
    type: InstrumentFlowType,
    amount: String,
    instrument: UUID = SOL,
    wallet: String = WALLET,
    decimals: Int = 9,
    occurred: String = "2025-01-10T00:00:00Z",
    recorded: String = "2025-01-10T00:00:10Z",
    supersedes: UUID? = null,
) = InstrumentFlow(
    id = UUID.randomUUID(),
    externalId = "solana:sig:${UUID.randomUUID()}:bal:0",
    instrumentId = instrument,
    wallet = wallet,
    tokenAccount = null,
    flowType = type,
    amountRaw = BigInteger(amount),
    decimals = decimals,
    occurredAt = Instant.parse(occurred),
    recordedAt = Instant.parse(recorded),
    slot = 100,
    signature = "sig",
    supersedesId = supersedes,
)

class InstrumentFlowTest {
    @Test
    fun `net position signs inbound and outbound flows`() {
        val flows =
            listOf(
                flow(InstrumentFlowType.TRANSFER_IN, "1000"),
                flow(InstrumentFlowType.TRANSFER_OUT, "300"),
                flow(InstrumentFlowType.STAKING_REWARD, "50"),
                flow(InstrumentFlowType.BURN, "20"),
            )

        val p = tokenPositions(flows, NOW).single()

        assertEquals(BigInteger("730"), p.netRaw)
        assertEquals(BigInteger("1050"), p.inboundRaw)
        assertEquals(BigInteger("320"), p.outboundRaw)
        assertEquals(BigInteger.ZERO, p.unclassifiedRaw)
    }

    @Test
    fun `other flows are facts but never signed into net`() {
        val flows = listOf(flow(InstrumentFlowType.TRANSFER_IN, "100"), flow(InstrumentFlowType.OTHER, "7"))

        val p = tokenPositions(flows, NOW).single()

        assertEquals(BigInteger("100"), p.netRaw)
        assertEquals(BigInteger("7"), p.unclassifiedRaw)
    }

    @Test
    fun `positions group by instrument and wallet`() {
        val flows =
            listOf(
                flow(InstrumentFlowType.TRANSFER_IN, "10"),
                flow(InstrumentFlowType.TRANSFER_IN, "20", instrument = BONK, decimals = 5),
                flow(InstrumentFlowType.TRANSFER_IN, "4", wallet = OTHER_WALLET),
                flow(InstrumentFlowType.TRANSFER_OUT, "3", wallet = OTHER_WALLET),
            )

        val positions = tokenPositions(flows, NOW).associateBy { it.instrumentId to it.wallet }

        assertEquals(BigInteger("10"), positions.getValue(SOL to WALLET).netRaw)
        assertEquals(BigInteger("20"), positions.getValue(BONK to WALLET).netRaw)
        assertEquals(5, positions.getValue(BONK to WALLET).decimals)
        assertEquals(BigInteger("1"), positions.getValue(SOL to OTHER_WALLET).netRaw)
    }

    @Test
    fun `a correction chain resolves to its last link`() {
        val original = flow(InstrumentFlowType.TRANSFER_IN, "100")
        val fix1 = flow(InstrumentFlowType.TRANSFER_IN, "120", recorded = "2025-02-01T00:00:00Z", supersedes = original.id)
        val fix2 = flow(InstrumentFlowType.TRANSFER_OUT, "30", recorded = "2025-03-01T00:00:00Z", supersedes = fix1.id)

        assertEquals(BigInteger("-30"), tokenPositions(listOf(original, fix1, fix2), NOW).single().netRaw)
    }

    @Test
    fun `knownAt rebuilds the position as it was known before the correction`() {
        val original = flow(InstrumentFlowType.TRANSFER_IN, "100")
        val fix = flow(InstrumentFlowType.TRANSFER_IN, "150", recorded = "2025-04-01T00:00:00Z", supersedes = original.id)
        val flows = listOf(original, fix)

        assertEquals(BigInteger("100"), tokenPositions(flows, Instant.parse("2025-03-01T00:00:00Z")).single().netRaw)
        assertEquals(BigInteger("150"), tokenPositions(flows, NOW).single().netRaw)
    }

    @Test
    fun `mixed decimals on one instrument are rejected`() {
        val flows = listOf(flow(InstrumentFlowType.TRANSFER_IN, "1"), flow(InstrumentFlowType.TRANSFER_IN, "1", decimals = 6))

        assertFailsWith<IllegalArgumentException> { tokenPositions(flows, NOW) }
    }

    @Test
    fun `a forked correction and a dangling supersedes are rejected`() {
        val original = flow(InstrumentFlowType.TRANSFER_IN, "100")
        val a = flow(InstrumentFlowType.TRANSFER_IN, "1", supersedes = original.id)
        val b = flow(InstrumentFlowType.TRANSFER_IN, "2", supersedes = original.id)
        val orphan = flow(InstrumentFlowType.TRANSFER_IN, "1", supersedes = UUID.randomUUID())

        assertFailsWith<IllegalStateException> { tokenPositions(listOf(original, a, b), NOW) }
        assertFailsWith<IllegalStateException> { tokenPositions(listOf(original, orphan), NOW) }
    }

    @Test
    fun `flow types match the ontology`() {
        val ontology = File(System.getProperty("ontology.file") ?: error("ontology.file system property is not set")).readText()
        val values =
            Regex("""attribute\s+instrument-flow-type,\s+value\s+string\s+@values\(([^)]*)\)""")
                .find(ontology)
                ?.groupValues
                ?.get(1)
                ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toSet() }

        assertEquals(values, InstrumentFlowType.entries.map { it.wireValue }.toSet())
    }
}
