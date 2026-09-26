package com.mesta.asset.analytics

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private fun d(value: String) = BigDecimal(value)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, d(expected).compareTo(actual), "expected $expected but was $actual")
}

// Five peers between 6x and 10x EV/EBITDA, and one at 25x.
private val PEERS =
    listOf("A" to "6", "B" to "7", "C" to "8", "D" to "9", "E" to "10", "F" to "25").map { (peer, multiple) ->
        PeerMultiple(peer, d(multiple))
    }

private val RUN =
    ComparablesInputs(
        valuationDate = LocalDate.parse("2026-06-30"),
        currency = Currency.getInstance("USD"),
        metric = "EBITDA",
        metricPeriod = "LTM to 2026-06-30",
        companyMetric = d("50"),
        peers = PEERS,
        outlierPolicy = OutlierPolicy.TukeyFences(d("1.5")),
        adjustments = listOf(ValuationAdjustment("illiquidity discount", d("0.8"))),
    )

class ComparablesTest {
    @Test
    fun `tukey fences drop the outlier and the range comes from the rest`() {
        val valuation = comparables(RUN)

        // Quartiles of all six are 7.25 and 9.75, so the fences are [3.5, 13.5] and 25x falls outside.
        assertEquals(listOf("F"), valuation.excludedPeers.map { it.peer })
        assertEquals(5, valuation.usedPeers.size)
        assertDecimal("7", valuation.lowMultiple) // NumPy percentile([6, 7, 8, 9, 10], [25, 50, 75]) = 7, 8, 9
        assertDecimal("8", valuation.medianMultiple)
        assertDecimal("9", valuation.highMultiple)
        assertDecimal("280", valuation.lowValue) // 50 × 7 × 0.8
        assertDecimal("320", valuation.medianValue)
        assertDecimal("360", valuation.highValue)
        assertEquals(RUN, valuation.inputs, "the run keeps its peer set, period, policy and adjustments")
        assertEquals(COMPARABLES_METHODOLOGY, valuation.methodology)
    }

    @Test
    fun `keeping every peer interpolates between order statistics`() {
        val valuation = comparables(RUN.copy(outlierPolicy = OutlierPolicy.KeepAll, adjustments = emptyList()))

        assertEquals(emptyList(), valuation.excludedPeers)
        assertDecimal("7.25", valuation.lowMultiple) // NumPy: 7.25, 8.5, 9.75
        assertDecimal("8.5", valuation.medianMultiple)
        assertDecimal("9.75", valuation.highMultiple)
        assertDecimal("425", valuation.medianValue) // 50 × 8.5
    }

    @Test
    fun `percentile matches numpy's default on an even count`() {
        val values = listOf(d("1"), d("2"), d("3"), d("4"))

        assertDecimal("1.75", percentile(values, d("0.25")))
        assertDecimal("2.5", percentile(values, d("0.5")))
        assertDecimal("3.25", percentile(values, d("0.75")))
        assertDecimal("4", percentile(values, d("1")))
    }

    @Test
    fun `invalid runs are rejected`() {
        assertFailsWith<IllegalArgumentException> { comparables(RUN.copy(peers = PEERS.take(2))) }
        assertFailsWith<IllegalArgumentException> { RUN.copy(companyMetric = d("-5")) }
        assertFailsWith<IllegalArgumentException> { RUN.copy(peers = PEERS + PeerMultiple("A", d("7"))) }
        assertFailsWith<IllegalArgumentException> { PeerMultiple("G", d("-3")) }
        assertFailsWith<IllegalArgumentException> { ValuationAdjustment("control premium", d("0")) }
        assertFailsWith<IllegalArgumentException> { OutlierPolicy.TukeyFences(d("0")) }
        assertFailsWith<IllegalArgumentException> { percentile(emptyList(), d("0.5")) }
    }
}
