package com.mesta.asset.iborcore

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun valuation(
    amount: String,
    asOf: String,
    recorded: String = "2024-12-31T00:00:00Z",
    supersedes: UUID? = null,
) = ValuationEvent(
    id = UUID.randomUUID(),
    amount = BigDecimal(amount),
    currency = Currency.getInstance("USD"),
    asOfDate = LocalDate.parse(asOf),
    method = ValuationMethod.MARK_TO_MODEL,
    recordedAt = Instant.parse(recorded),
    supersedesId = supersedes,
)

class ValuationTest {
    private val q2 = valuation("110", "2024-06-30")
    private val q3 = valuation("120", "2024-09-30")
    private val q4 = valuation("130", "2024-12-31")
    private val all = listOf(q2, q3, q4)
    private val members = all.mapTo(HashSet()) { it.id }
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    private fun latest(
        rows: List<ValuationEvent> = all,
        asOf: String = "2024-12-31",
        knownAt: Instant = now,
        ids: Set<UUID> = rows.mapTo(HashSet()) { it.id },
    ) = latestValuation(rows, ids, LocalDate.parse(asOf), knownAt)

    @Test
    fun `picks the latest valuation on or before the as-of date`() {
        assertEquals(q4, latest())
        assertEquals(q3, latest(asOf = "2024-11-15"))
    }

    @Test
    fun `nothing on or before the date is undefined`() {
        assertNull(latest(asOf = "2024-01-01"))
        assertNull(latest(ids = emptySet()))
    }

    @Test
    fun `a correction replaces the original unless it was recorded after knownAt`() {
        val fix = valuation("125", "2024-12-31", "2025-01-15T00:00:00Z", q4.id)
        val rows = all + fix

        assertEquals(fix, latest(rows, knownAt = Instant.parse("2025-02-01T00:00:00Z")))
        assertEquals(q4, latest(rows, knownAt = now, ids = members + fix.id))
    }

    @Test
    fun `two current valuations on the same date are a conflict`() {
        assertFailsWith<IllegalStateException> { latest(all + valuation("999", "2024-12-31")) }
    }

    @Test
    fun `valuation methods match the ontology`() {
        val ontology = File(System.getProperty("ontology.file") ?: error("ontology.file system property is not set")).readText()
        val values =
            Regex("""attribute\s+valuation-method,\s+value\s+string\s+@values\(([^)]*)\)""")
                .find(ontology)
                ?.groupValues
                ?.get(1)
                ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toSet() }

        assertEquals(values, ValuationMethod.entries.map { it.wireValue }.toSet())
        assertEquals(ValuationMethod.RECENT_ROUND, ValuationMethod.fromWireValue("recent-round"))
        assertFailsWith<IllegalStateException> { ValuationMethod.fromWireValue("vibes") }
    }
}
