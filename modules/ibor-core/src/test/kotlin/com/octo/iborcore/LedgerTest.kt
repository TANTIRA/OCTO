package com.mesta.asset.iborcore

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val USD = Currency.getInstance("USD")
private val NOW = Instant.parse("2025-01-01T00:00:00Z")

private fun event(
    type: FlowType,
    amount: String,
    occurred: String,
    recorded: String = "2024-12-31T00:00:00Z",
    supersedes: UUID? = null,
    currency: Currency = USD,
) = LedgerEvent(
    id = UUID.randomUUID(),
    flowType = type,
    amount = BigDecimal(amount),
    currency = currency,
    occurredAt = Instant.parse(occurred),
    recordedAt = Instant.parse(recorded),
    supersedesId = supersedes,
)

private fun position(
    ledger: List<LedgerEvent>,
    members: Collection<LedgerEvent> = ledger,
    knownAt: Instant = NOW,
    zone: ZoneId = ZoneOffset.UTC,
) = commitmentPosition(ledger, members.mapTo(HashSet()) { it.id }, knownAt, zone)

private fun valuation(
    amount: String,
    asOf: String,
    currency: Currency = USD,
) = ValuationEvent(
    id = UUID.randomUUID(),
    amount = BigDecimal(amount),
    currency = currency,
    asOfDate = LocalDate.parse(asOf),
    method = ValuationMethod.MARK_TO_MODEL,
    recordedAt = Instant.parse("2024-12-31T00:00:00Z"),
)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal,
) = assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")

class LedgerTest {
    // Synthetic commitment: two calls, one distribution, one recallable distribution, plus non-investor flows.
    private val call1 = event(FlowType.CONTRIBUTION, "-100", "2022-01-10T00:00:00Z")
    private val call2 = event(FlowType.CONTRIBUTION, "-50", "2023-01-10T00:00:00Z")
    private val dist = event(FlowType.DISTRIBUTION, "60", "2024-01-10T00:00:00Z")
    private val recall = event(FlowType.RECALLABLE_DISTRIBUTION, "10", "2024-02-10T00:00:00Z")
    private val fee = event(FlowType.MANAGEMENT_FEE, "-2", "2022-06-30T00:00:00Z")
    private val expense = event(FlowType.EXPENSE, "-1", "2022-06-30T00:00:00Z")
    private val carry = event(FlowType.CARRIED_INTEREST, "-3", "2024-01-10T00:00:00Z")
    private val income = event(FlowType.OTHER_INCOME, "4", "2024-01-10T00:00:00Z")
    private val ledger = listOf(dist, call2, call1, recall, fee, expense, carry, income)

    @Test
    fun `totals per flow type with only investor flows in the series`() {
        val p = position(ledger)

        assertDecimal("150", p.called)
        assertDecimal("60", p.distributed)
        assertDecimal("10", p.recallableDistributed)
        assertDecimal("2", p.fees)
        assertDecimal("1", p.expenses)
        assertDecimal("3", p.carriedInterest)
        assertDecimal("4", p.otherIncome)
        assertEquals(
            listOf("2022-01-10", "2023-01-10", "2024-01-10", "2024-02-10").map(LocalDate::parse),
            p.cashFlows.map { it.first },
        )
        assertEquals(listOf("-100", "-50", "60", "10").map(::BigDecimal), p.cashFlows.map { it.second })
    }

    @Test
    fun `a correction chain resolves to its last link`() {
        val fix1 = event(FlowType.CONTRIBUTION, "-120", "2022-01-10T00:00:00Z", "2024-12-01T00:00:00Z", call1.id)
        val fix2 = event(FlowType.CONTRIBUTION, "-110", "2022-01-10T00:00:00Z", "2024-12-15T00:00:00Z", fix1.id)
        val corrected = ledger + fix1 + fix2

        assertDecimal("160", position(corrected).called)
    }

    @Test
    fun `knownAt rebuilds the position as it was known before the correction`() {
        val fix = event(FlowType.CONTRIBUTION, "-120", "2022-01-10T00:00:00Z", "2024-12-31T12:00:00Z", call1.id)

        assertDecimal("150", position(ledger + fix, knownAt = Instant.parse("2024-12-31T06:00:00Z")).called)
        assertDecimal("170", position(ledger + fix).called)
    }

    @Test
    fun `a correction attributed elsewhere removes the event from this commitment`() {
        val moved = event(FlowType.CONTRIBUTION, "-50", "2023-01-10T00:00:00Z", "2024-12-31T12:00:00Z", call2.id)

        assertDecimal("100", position(ledger + moved, members = ledger).called)
    }

    @Test
    fun `dates follow the caller's zone`() {
        val lateCall = event(FlowType.CONTRIBUTION, "-5", "2024-03-31T20:00:00Z")

        assertEquals(LocalDate.parse("2024-04-01"), position(listOf(lateCall), zone = ZoneId.of("Asia/Jakarta")).cashFlows.single().first)
        assertEquals(LocalDate.parse("2024-03-31"), position(listOf(lateCall)).cashFlows.single().first)
    }

    @Test
    fun `a forked correction is rejected`() {
        val a = event(FlowType.CONTRIBUTION, "-1", "2022-01-10T00:00:00Z", supersedes = call1.id)
        val b = event(FlowType.CONTRIBUTION, "-2", "2022-01-10T00:00:00Z", supersedes = call1.id)

        assertFailsWith<IllegalStateException> { position(ledger + a + b) }
    }

    @Test
    fun `a dangling supersedes reference is rejected`() {
        val orphan = event(FlowType.CONTRIBUTION, "-1", "2022-01-10T00:00:00Z", supersedes = UUID.randomUUID())

        assertFailsWith<IllegalStateException> { position(ledger + orphan) }
    }

    @Test
    fun `mixed currencies and empty commitments are rejected`() {
        val eur = event(FlowType.CONTRIBUTION, "-1", "2022-01-10T00:00:00Z", currency = Currency.getInstance("EUR"))

        assertFailsWith<IllegalArgumentException> { position(ledger + eur) }
        assertFailsWith<IllegalArgumentException> { position(ledger, members = emptyList()) }
    }

    @Test
    fun `flow types match the ontology`() {
        val ontology = File(System.getProperty("ontology.file") ?: error("ontology.file system property is not set")).readText()
        val values =
            Regex("""attribute\s+flow-type,\s+value\s+string\s+@values\(([^)]*)\)""")
                .find(ontology)
                ?.groupValues
                ?.get(1)
                ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toSet() }

        assertEquals(values, FlowType.entries.map { it.wireValue }.toSet())
    }

    @Test
    fun `deal position sums the attributed investor flows and takes the valuation in force`() {
        val nav = valuation("80", "2024-06-30")
        val deal =
            dealPosition(
                ledger,
                ledger.mapTo(HashSet()) { it.id },
                listOf(nav),
                setOf(nav.id),
                LocalDate.parse("2024-12-31"),
                NOW,
                ZoneOffset.UTC,
            )

        assertDecimal("150", deal.invested) // 100 + 50
        assertDecimal("70", deal.realized) // 60 + 10 recallable; fees, expenses, carry and other income excluded
        assertDecimal("80", deal.unrealized!!)
        assertEquals(LocalDate.parse("2024-06-30"), deal.valuationDate)
    }

    @Test
    fun `deal position without a valuation is unrealized-unknown and a foreign-currency valuation is rejected`() {
        val members = setOf(call1.id, dist.id)
        val deal = dealPosition(ledger, members, emptyList(), emptySet(), LocalDate.parse("2024-12-31"), NOW, ZoneOffset.UTC)
        assertDecimal("100", deal.invested)
        assertDecimal("60", deal.realized)
        assertEquals(null, deal.unrealized)

        val eur = valuation("80", "2024-06-30", currency = Currency.getInstance("EUR"))
        assertFailsWith<IllegalArgumentException> {
            dealPosition(ledger, members, listOf(eur), setOf(eur.id), LocalDate.parse("2024-12-31"), NOW, ZoneOffset.UTC)
        }
    }
}
