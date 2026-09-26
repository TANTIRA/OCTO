package com.mesta.asset.analytics

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val USD = Currency.getInstance("USD")
private val START = LocalDate.parse("2025-01-01")
private val END = LocalDate.parse("2025-12-31")

private fun sector(
    name: String,
    wp: String,
    wb: String,
    rp: String?,
    rb: String?,
) = SectorPerformance(name, BigDecimal(wp), BigDecimal(wb), rp?.let { BigDecimal(it) }, rb?.let { BigDecimal(it) })

private fun attribute(vararg sectors: SectorPerformance) = brinson(USD, "synthetic-index", START, END, sectors.toList())

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
}

class AttributionTest {
    @Test
    fun `effects follow methodology 4_2`() {
        // r_p = 0.6·10% + 0.4·5% = 8%; r_b = 0.5·8% + 0.5·4% = 6%.
        val report = attribute(sector("A", "0.6", "0.5", "0.10", "0.08"), sector("B", "0.4", "0.5", "0.05", "0.04"))
        val (a, b) = report.sectors

        assertDecimal("0.002", a.allocation) // (0.6 − 0.5)(8% − 6%)
        assertDecimal("0.01", a.selection) // 0.5(10% − 8%)
        assertDecimal("0.002", a.interaction) // (0.6 − 0.5)(10% − 8%)
        assertDecimal("0.002", b.allocation) // (0.4 − 0.5)(4% − 6%)
        assertDecimal("0.005", b.selection) // 0.5(5% − 4%)
        assertDecimal("-0.001", b.interaction) // (0.4 − 0.5)(5% − 4%)
        assertDecimal("0.08", report.portfolioReturn)
        assertDecimal("0.06", report.benchmarkReturn)
        assertDecimal("0.004", report.allocation)
        assertDecimal("0.015", report.selection)
        assertDecimal("0.001", report.interaction)
        assertEquals(ATTRIBUTION_METHODOLOGY, report.methodology)
    }

    @Test
    fun `the three effects add up to the active return`() {
        val report =
            attribute(
                sector("Tech", "0.5", "0.3", "0.12", "0.10"),
                sector("Health", "0.2", "0.3", "-0.03", "0.01"),
                sector("Energy", "0.3", "0.4", "0.05", "0.07"),
            )

        assertDecimal("0.008", report.activeReturn)
        assertDecimal("0.008", report.allocation!! + report.selection!! + report.interaction!!)
    }

    @Test
    fun `a benchmark sector the portfolio does not hold has undefined selection and interaction`() {
        val report = attribute(sector("A", "1", "0.6", "0.10", "0.08"), sector("B", "0", "0.4", null, "0.02"))
        val b = report.sectors[1]

        assertDecimal("0.0144", b.allocation) // (0 − 0.4)(2% − 5.6%)
        assertNull(b.selection)
        assertNull(b.interaction)
        assertDecimal("0.024", report.allocation)
        assertNull(report.selection, "a total over an undefined effect is undefined, not a partial sum")
        assertNull(report.interaction)
        assertDecimal("0.044", report.activeReturn)
    }

    @Test
    fun `a portfolio sector outside the benchmark has undefined allocation and interaction`() {
        val z = attribute(sector("A", "0.7", "1", "0.10", "0.05"), sector("Z", "0.3", "0", "0.20", null)).sectors[1]

        assertNull(z.allocation)
        assertDecimal("0", z.selection) // zero benchmark weight
        assertNull(z.interaction)
    }

    @Test
    fun `weights that do not sum to one are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            attribute(sector("A", "0.6", "0.5", "0.10", "0.08"), sector("B", "0.3", "0.5", "0.05", "0.04"))
        }
    }

    @Test
    fun `a held sector without a return is rejected`() {
        assertFailsWith<IllegalArgumentException> { sector("A", "0.5", "0.5", null, "0.08") }
        assertFailsWith<IllegalArgumentException> { sector("A", "0.5", "0.5", "0.10", null) }
    }

    @Test
    fun `duplicate sectors and an empty period are rejected`() {
        val half = sector("A", "0.5", "0.5", "0.10", "0.08")
        assertFailsWith<IllegalArgumentException> { attribute(half, half) }

        val whole = sector("A", "1", "1", "0.10", "0.08")
        assertFailsWith<IllegalArgumentException> { brinson(USD, "synthetic-index", END, END, listOf(whole)) }
    }
}
