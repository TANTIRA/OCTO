package com.mesta.asset.recon.matching

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val USD = Currency.getInstance("USD")
private val EUR = Currency.getInstance("EUR")
private val D = LocalDate.parse("2026-06-30")

private fun source(
    id: String,
    amount: String,
    date: LocalDate = D,
    currency: Currency = USD,
    system: String = "admin-a",
) = SourceRecord(system, id, BigDecimal(amount), currency, date)

private fun ibor(
    id: String?,
    amount: String,
    date: LocalDate = D,
    currency: Currency = USD,
    system: String = "admin-a",
) = IborRecord(UUID.randomUUID(), system, id, BigDecimal(amount), currency, date)

class ReconciliationTest {
    @Test
    fun `exact matches raise no break and unmatched sides raise one each`() {
        val kept = ibor("t-1", "-100")
        val orphan = ibor("t-9", "5")
        val result = reconcile(listOf(source("t-1", "-100"), source("t-2", "40")), listOf(kept, orphan))

        assertEquals(listOf("t-1"), result.matched.map { it.first.externalId })
        assertEquals(listOf(BreakKind.MISSING_IN_IBOR, BreakKind.MISSING_IN_SOURCE), result.breaks.map { it.kind })
        assertEquals("t-2", result.breaks[0].sourceRef)
        assertEquals(orphan.id, result.breaks[1].ledgerEventId)
        assertEquals(RECONCILIATION_RULESET, result.ruleset)
    }

    @Test
    fun `a matched pair raises one break per differing field, within tolerance none`() {
        val event = ibor("t-1", "-100.05", D.plusDays(1), EUR)
        val strict = reconcile(listOf(source("t-1", "-100")), listOf(event))
        assertEquals(listOf(BreakKind.CURRENCY_MISMATCH, BreakKind.AMOUNT_MISMATCH, BreakKind.DATE_MISMATCH), strict.breaks.map { it.kind })
        assertEquals(mapOf("source" to "-100", "ibor" to "-100.05"), strict.breaks[1].detail)
        assertTrue(strict.breaks.all { it.ledgerEventId == event.id && it.sourceRef == "t-1" })

        val lenient = reconcile(listOf(source("t-1", "-100", currency = EUR)), listOf(event), Tolerance(BigDecimal("0.05"), 1))
        assertTrue(lenient.breaks.isEmpty())
        assertEquals(1, lenient.matched.size)
    }

    @Test
    fun `other source systems are out of scope and an event without an external id is a finding`() {
        val other = ibor("x-1", "1", system = "admin-b")
        val unattributed = ibor(null, "7")
        val result = reconcile(listOf(source("t-1", "1")), listOf(ibor("t-1", "1"), other, unattributed))

        assertEquals(listOf(BreakKind.MISSING_IN_SOURCE), result.breaks.map { it.kind })
        assertEquals(unattributed.id, result.breaks.single().ledgerEventId)
        assertEquals(null, result.breaks.single().sourceRef)
    }

    @Test
    fun `duplicate keys on either side are refused rather than guessed`() {
        assertFailsWith<IllegalArgumentException> { reconcile(listOf(source("t-1", "1"), source("t-1", "2")), emptyList()) }
        assertFailsWith<IllegalStateException> { reconcile(listOf(source("t-1", "1")), listOf(ibor("t-1", "1"), ibor("t-1", "1"))) }
        assertFailsWith<IllegalArgumentException> { Tolerance(BigDecimal("-1"), 0) }
        assertFailsWith<IllegalArgumentException> { source(" ", "1") }
    }
}
