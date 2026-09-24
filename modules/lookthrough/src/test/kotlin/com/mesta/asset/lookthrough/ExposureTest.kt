package com.mesta.asset.lookthrough

import java.math.BigDecimal
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private val USD = Currency.getInstance("USD")

private fun edge(
    holder: String,
    held: String,
    fraction: String,
) = OwnershipEdge(holder, held, BigDecimal(fraction))

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
}

class ExposureTest {
    // Synthetic structure: LP holds two funds that both own company C.
    private val edges =
        listOf(
            edge("lp", "fund-a", "0.5"),
            edge("lp", "fund-b", "0.5"),
            edge("fund-a", "co-c", "0.4"),
            edge("fund-a", "co-d", "0.6"),
            edge("fund-b", "co-c", "1"),
        )

    @Test
    fun `exposure sums every path to the same company`() {
        val report = lookThrough("lp", BigDecimal("1000"), USD, edges)

        // co-c: 1000*0.5*0.4 + 1000*0.5*1 = 200 + 500
        assertDecimal("700", report.byAsset["co-c"])
        assertDecimal("300", report.byAsset["co-d"])
        assertEquals(setOf("co-c", "co-d"), report.byAsset.keys)
    }

    @Test
    fun `report measures split long and short`() {
        val report = lookThrough("lp", BigDecimal("1000"), USD, edges + edge("fund-a", "co-e", "-0.1"))

        assertDecimal("700", report.byAsset["co-c"])
        assertDecimal("-50", report.byAsset["co-e"])
        assertDecimal("950", report.netExposure)
        assertDecimal("1000", report.longExposure)
        assertDecimal("-50", report.shortExposure)
        assertDecimal("1050", report.grossExposure)
        assertEquals(3, report.assetCount)
        assertEquals(2, report.assetCountLong)
        assertEquals(1, report.assetCountShort)
    }

    @Test
    fun `a root with no holdings is its own exposure`() {
        val report = lookThrough("co-x", BigDecimal("10"), USD, edges)
        assertDecimal("10", report.byAsset["co-x"])
    }

    @Test
    fun `an ownership cycle is rejected`() {
        val cyclic = edges + edge("co-c", "fund-a", "0.1")
        val error = assertFailsWith<IllegalStateException> { lookThrough("lp", BigDecimal("1000"), USD, cyclic) }
        assert("cycle" in error.message.orEmpty())
    }

    @Test
    fun `ownership above one hundred percent is rejected`() {
        assertFailsWith<IllegalArgumentException> { edge("lp", "fund-a", "1.2") }
        assertFailsWith<IllegalArgumentException> { edge("lp", "lp", "0.5") }
    }
}
