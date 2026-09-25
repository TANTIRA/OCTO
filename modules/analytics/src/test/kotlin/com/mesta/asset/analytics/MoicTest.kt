package com.mesta.asset.analytics

import java.math.BigDecimal
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val USD = Currency.getInstance("USD")

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
}

class MoicTest {
    @Test
    fun `moic follows methodology 2_2 with its realized and unrealized parts`() {
        val m = moic(USD, BigDecimal("100"), BigDecimal("150"), BigDecimal("50"))

        assertDecimal("2", m.moic)
        assertDecimal("1.5", m.realizedMultiple)
        assertDecimal("0.5", m.unrealizedMultiple)
        assertEquals(MOIC_METHODOLOGY, m.methodology)
    }

    @Test
    fun `nothing invested leaves every multiple undefined`() {
        val m = moic(USD, BigDecimal.ZERO, BigDecimal("10"), BigDecimal("5"))

        assertNull(m.moic)
        assertNull(m.realizedMultiple)
        assertNull(m.unrealizedMultiple)
    }

    @Test
    fun `a missing valuation leaves moic undefined but the realized multiple defined`() {
        val m = moic(USD, BigDecimal("100"), BigDecimal("40"), null)

        assertNull(m.moic)
        assertNull(m.unrealizedMultiple)
        assertDecimal("0.4", m.realizedMultiple)
    }

    @Test
    fun `negative magnitudes are rejected`() {
        assertFailsWith<IllegalArgumentException> { moic(USD, BigDecimal("-1"), BigDecimal.ZERO, null) }
        assertFailsWith<IllegalArgumentException> { moic(USD, BigDecimal.ONE, BigDecimal("-1"), null) }
        assertFailsWith<IllegalArgumentException> { moic(USD, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal("-1")) }
    }
}
