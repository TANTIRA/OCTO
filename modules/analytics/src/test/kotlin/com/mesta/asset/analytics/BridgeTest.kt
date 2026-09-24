package com.mesta.asset.analytics

import com.mesta.asset.analytics.BridgeDriver.FX
import com.mesta.asset.analytics.BridgeDriver.MARGIN
import com.mesta.asset.analytics.BridgeDriver.MULTIPLE
import com.mesta.asset.analytics.BridgeDriver.NET_DEBT
import com.mesta.asset.analytics.BridgeDriver.REVENUE
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val USD = Currency.getInstance("USD")
private val EUR = Currency.getInstance("EUR")

private fun point(
    date: String,
    revenue: String,
    margin: String,
    multiple: String,
    netDebt: String,
    fx: String,
) = BridgePoint(LocalDate.parse(date), BigDecimal(revenue), BigDecimal(margin), BigDecimal(multiple), BigDecimal(netDebt), BigDecimal(fx))

// A EUR company reported in USD. Entry equity (100·0.20·8 − 60)·1.0 = 100; exit (150·0.25·10 − 40)·1.1 = 368.5.
private val ENTRY = point("2019-06-30", "100", "0.20", "8", "60", "1.0")
private val EXIT = point("2024-06-30", "150", "0.25", "10", "40", "1.1")

private fun bridge(method: BridgeMethod) = valueBridge(ENTRY, EXIT, EUR, USD, method)

private fun total(effects: Map<BridgeDriver, BigDecimal>) = effects.values.fold(BigDecimal.ZERO, BigDecimal::add)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
}

private fun <T> permutations(items: List<T>): List<List<T>> =
    if (items.size <= 1) listOf(items) else items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }

class BridgeTest {
    @Test
    fun `a sequential bridge attributes each driver in the stated order and adds up exactly`() {
        val ordering = listOf(REVENUE, MARGIN, MULTIPLE, NET_DEBT, FX)
        val result = bridge(BridgeMethod.Sequential(ordering))

        assertDecimal("80", result.effects[REVENUE]) // EV 160 → 240
        assertDecimal("60", result.effects[MARGIN]) // EV 240 → 300
        assertDecimal("75", result.effects[MULTIPLE]) // EV 300 → 375
        assertDecimal("20", result.effects[NET_DEBT]) // net debt 60 → 40
        assertDecimal("33.5", result.effects[FX]) // 335 × (1.1 − 1.0)
        assertDecimal("268.5", result.change)
        assertDecimal("268.5", total(result.effects))
        assertEquals(ordering, result.effects.keys.toList(), "the ordering is stored with the effects")
        assertEquals(BridgeMethod.Sequential(ordering), result.method)
        assertEquals(BRIDGE_METHODOLOGY, result.methodology)
    }

    @Test
    fun `another ordering gives other effects with the same total`() {
        val result = bridge(BridgeMethod.Sequential(listOf(FX, NET_DEBT, MULTIPLE, MARGIN, REVENUE)))

        assertDecimal("10", result.effects[FX]) // (160 − 60) × 0.1
        assertDecimal("22", result.effects[NET_DEBT]) // 20 × 1.1
        assertDecimal("44", result.effects[MULTIPLE]) // (200 − 160) × 1.1
        assertDecimal("55", result.effects[MARGIN]) // (250 − 200) × 1.1
        assertDecimal("137.5", result.effects[REVENUE]) // (375 − 250) × 1.1
        assertDecimal("268.5", total(result.effects))
    }

    @Test
    fun `shapley splits a two-driver interaction evenly and gives unchanged drivers nothing`() {
        val entry = point("2019-06-30", "100", "1", "8", "0", "1")
        val exit = point("2024-06-30", "150", "1", "10", "0", "1")
        val result = valueBridge(entry, exit, USD, USD, BridgeMethod.Shapley)

        // Equity is revenue × multiple: φ_R = ½[50·8 + 50·10] = 450 and φ_X = ½[100·2 + 150·2] = 250.
        assertDecimal("450", result.effects[REVENUE])
        assertDecimal("250", result.effects[MULTIPLE])
        assertDecimal("0", result.effects[MARGIN])
        assertDecimal("0", result.effects[NET_DEBT])
        assertDecimal("0", result.effects[FX])
        assertDecimal("700", result.change)
    }

    @Test
    fun `shapley equals the average sequential effect over every ordering`() {
        val shapley = bridge(BridgeMethod.Shapley).effects
        val orderings = permutations(BridgeDriver.entries)
        val tolerance = BigDecimal("1e-12")

        assertEquals(120, orderings.size)
        for (driver in BridgeDriver.entries) {
            val average =
                orderings
                    .map { bridge(BridgeMethod.Sequential(it)).effects.getValue(driver) }
                    .fold(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal(orderings.size), MathContext.DECIMAL64)
            assertTrue((average - shapley.getValue(driver)).abs() <= tolerance, "$driver: $average vs ${shapley[driver]}")
        }
        assertTrue((total(shapley) - BigDecimal("268.5")).abs() <= tolerance, "Shapley effects add up to the change")
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { BridgeMethod.Sequential(listOf(REVENUE, MARGIN, MULTIPLE, NET_DEBT)) }
        assertFailsWith<IllegalArgumentException> { BridgeMethod.Sequential(listOf(REVENUE, REVENUE, MULTIPLE, NET_DEBT, FX)) }
        assertFailsWith<IllegalArgumentException> { valueBridge(EXIT, ENTRY, EUR, USD, BridgeMethod.Shapley) }
        assertFailsWith<IllegalArgumentException> { valueBridge(ENTRY, EXIT, USD, USD, BridgeMethod.Shapley) } // fx 1.1 in one currency
        assertFailsWith<IllegalArgumentException> { point("2019-06-30", "-1", "0.2", "8", "0", "1") }
        assertFailsWith<IllegalArgumentException> { point("2019-06-30", "100", "0.2", "-8", "0", "1") }
        assertFailsWith<IllegalArgumentException> { point("2019-06-30", "100", "0.2", "8", "0", "0") }
    }
}
