package com.octo.iborcore

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val USD = Currency.getInstance("USD")
private val EUR = Currency.getInstance("EUR")
private val KNOWN = Instant.parse("2025-01-01T00:00:00Z")

private fun event(
    type: FlowType,
    amount: String,
    occurred: String = "2022-06-01T12:00:00Z",
    recorded: String = "2022-06-01T12:00:00Z",
    supersedes: UUID? = null,
    currency: Currency = USD,
    id: UUID = UUID.randomUUID(),
) = LedgerEvent(
    id = id,
    flowType = type,
    amount = BigDecimal(amount),
    currency = currency,
    occurredAt = Instant.parse(occurred),
    recordedAt = Instant.parse(recorded),
    supersedesId = supersedes,
)

private fun journal(
    events: List<LedgerEvent>,
    zone: ZoneId = ZoneOffset.UTC,
    chart: GlChart = DEFAULT_GL_CHART,
) = glJournal(events, KNOWN, zone, chart)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal,
) = assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")

/** Net balance per account code: debits positive, credits negative. */
private fun netByAccount(j: GlJournal): Map<String, BigDecimal> {
    val net = HashMap<String, BigDecimal>()
    for (line in j.lines) {
        net.merge(line.debit.code, line.amount, BigDecimal::add)
        net.merge(line.credit.code, line.amount.negate(), BigDecimal::add)
    }
    return net
}

class GlExportTest {
    @Test
    fun `a capital call debits investments and credits cash at the magnitude`() {
        // Contributions are investor-signed negative; the journal posts the magnitude, direction in the accounts.
        val line = journal(listOf(event(FlowType.CONTRIBUTION, "-100"))).lines.single()
        assertEquals("1200", line.debit.code)
        assertEquals("1000", line.credit.code)
        assertDecimal("100", line.amount)
    }

    @Test
    fun `each flow type lands on the expected account pair`() {
        val pairs =
            journal(
                listOf(
                    event(FlowType.CONTRIBUTION, "-100"),
                    event(FlowType.DISTRIBUTION, "40"),
                    event(FlowType.RECALLABLE_DISTRIBUTION, "10"),
                    event(FlowType.MANAGEMENT_FEE, "-2"),
                    event(FlowType.EXPENSE, "-1"),
                    event(FlowType.CARRIED_INTEREST, "-3"),
                    event(FlowType.OTHER_INCOME, "5"),
                ),
            ).lines.associate { it.flowType to (it.debit.code to it.credit.code) }
        assertEquals("1200" to "1000", pairs[FlowType.CONTRIBUTION])
        assertEquals("1000" to "1200", pairs[FlowType.DISTRIBUTION])
        assertEquals("1000" to "1200", pairs[FlowType.RECALLABLE_DISTRIBUTION])
        assertEquals("6000" to "1000", pairs[FlowType.MANAGEMENT_FEE])
        assertEquals("6100" to "1000", pairs[FlowType.EXPENSE])
        assertEquals("6200" to "1000", pairs[FlowType.CARRIED_INTEREST])
        assertEquals("1000" to "4000", pairs[FlowType.OTHER_INCOME])
    }

    @Test
    fun `the journal balances per currency`() {
        val j =
            journal(
                listOf(
                    event(FlowType.CONTRIBUTION, "-100"),
                    event(FlowType.DISTRIBUTION, "40"),
                    event(FlowType.OTHER_INCOME, "5", currency = EUR),
                ),
            )
        assertEquals(j.debitsByCurrency, j.creditsByCurrency)
        assertDecimal("140", j.debitsByCurrency.getValue(USD))
        assertDecimal("5", j.debitsByCurrency.getValue(EUR))
    }

    @Test
    fun `a superseded event is not posted`() {
        val original = event(FlowType.CONTRIBUTION, "-100", recorded = "2022-06-01T12:00:00Z")
        val correction =
            event(FlowType.CONTRIBUTION, "-120", recorded = "2022-07-01T12:00:00Z", supersedes = original.id)
        val j = journal(listOf(original, correction))
        assertEquals(1, j.lines.size)
        assertEquals(correction.id, j.lines.single().sourceEventId)
        assertDecimal("120", j.debitsByCurrency.getValue(USD))
    }

    @Test
    fun `events recorded after the cut are excluded`() {
        val late = event(FlowType.DISTRIBUTION, "40", recorded = "2026-01-01T00:00:00Z")
        assertTrue(journal(listOf(late)).lines.isEmpty())
    }

    @Test
    fun `events occurring after the journal date are excluded even when recorded before the cut`() {
        // A capital call scheduled for after the cut, recorded before it, belongs to a later journal.
        val scheduled = event(FlowType.CONTRIBUTION, "-100", occurred = "2025-01-15T00:00:00Z", recorded = "2024-12-20T00:00:00Z")
        val sameDay = event(FlowType.DISTRIBUTION, "40", occurred = "2025-01-01T00:00:00Z", recorded = "2024-12-20T00:00:00Z")
        val j = journal(listOf(scheduled, sameDay))
        assertEquals(LocalDate.parse("2025-01-01"), j.asOf)
        assertEquals(listOf(sameDay.id), j.lines.map { it.sourceEventId })
        assertDecimal("40", j.debitsByCurrency.getValue(USD))
    }

    @Test
    fun `the accounting zone dates the entry`() {
        val nearMidnight = event(FlowType.DISTRIBUTION, "40", occurred = "2022-01-10T23:30:00Z")
        assertEquals(LocalDate.parse("2022-01-10"), journal(listOf(nearMidnight), ZoneOffset.UTC).lines.single().date)
        assertEquals(
            LocalDate.parse("2022-01-11"),
            journal(listOf(nearMidnight), ZoneOffset.ofHours(2)).lines.single().date,
        )
    }

    @Test
    fun `a custom chart overrides the account identifiers`() {
        val chart = DEFAULT_GL_CHART.copy(cash = GlAccount("CASH", "Bank"), investments = GlAccount("FUND", "LP Interests"))
        val line = journal(listOf(event(FlowType.CONTRIBUTION, "-100")), chart = chart).lines.single()
        assertEquals("FUND", line.debit.code)
        assertEquals("CASH", line.credit.code)
    }

    @Test
    fun `an empty ledger yields an empty journal`() {
        val j = journal(emptyList())
        assertTrue(j.lines.isEmpty())
        assertTrue(j.debitsByCurrency.isEmpty())
        assertEquals(LocalDate.parse("2025-01-01"), j.asOf)
    }

    @Test
    fun `a standalone reversal nets the original to zero on every account`() {
        val j = journal(listOf(event(FlowType.DISTRIBUTION, "100"), event(FlowType.DISTRIBUTION, "-100")))
        assertEquals(2, j.lines.size)
        val net = netByAccount(j)
        assertEquals(setOf("1000", "1200"), net.keys)
        net.values.forEach { assertDecimal("0", it) }
        val reversal = j.lines[1]
        assertEquals("1200" to "1000", reversal.debit.code to reversal.credit.code)
        assertDecimal("100", reversal.amount)
    }

    @Test
    fun `a management fee rebate credits fee expense`() {
        val j = journal(listOf(event(FlowType.MANAGEMENT_FEE, "-10"), event(FlowType.MANAGEMENT_FEE, "4")))
        val rebate = j.lines[1]
        assertEquals("1000" to "6000", rebate.debit.code to rebate.credit.code)
        assertDecimal("4", rebate.amount)
        assertDecimal("6", netByAccount(j).getValue("6000"))
        assertDecimal("-6", netByAccount(j).getValue("1000"))
    }

    @Test
    fun `a contribution refund debits cash and credits investments`() {
        val line = journal(listOf(event(FlowType.CONTRIBUTION, "25"))).lines.single()
        assertEquals("1000" to "1200", line.debit.code to line.credit.code)
        assertDecimal("25", line.amount)
    }

    @Test
    fun `every flow type posts its pair swapped when the sign is opposite`() {
        val normal = listOf("-1", "1", "1", "-1", "-1", "-1", "1")
        val types = FlowType.entries
        assertEquals(types.size, normal.size)
        val natural = journal(types.zip(normal) { t, a -> event(t, a) }).lines
        val opposite = journal(types.zip(normal) { t, a -> event(t, BigDecimal(a).negate().toPlainString()) }).lines
        natural.zip(opposite).forEach { (n, o) ->
            assertEquals(n.debit to n.credit, o.credit to o.debit, "${n.flowType}")
        }
    }
}
