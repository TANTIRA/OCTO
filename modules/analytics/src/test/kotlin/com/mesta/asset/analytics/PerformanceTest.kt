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

private fun flow(
    date: String,
    amount: String,
) = CashFlow(LocalDate.parse(date), BigDecimal(amount))

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
}

class PerformanceTest {
    // Synthetic fund: 150 called, 60 distributed, 120 residual NAV.
    private val fund =
        CashFlowSeries(
            currency = USD,
            flows = listOf(flow("2020-01-01", "-100"), flow("2021-01-01", "-50"), flow("2022-01-01", "60")),
            nav = BigDecimal("120"),
            valuationDate = LocalDate.parse("2022-12-31"),
        )

    @Test
    fun `multiples follow methodology 2_2`() {
        val report = performance(fund)

        assertDecimal("150", report.paidIn)
        assertDecimal("60", report.distributed)
        assertDecimal("0.4", report.dpi)
        assertDecimal("0.8", report.rvpi)
        assertDecimal("1.2", report.tvpi)
        assertEquals(PERFORMANCE_METHODOLOGY, report.methodology)
    }

    @Test
    fun `tvpi above one gives a positive irr`() {
        val irr = assertNotNull(performance(fund).irr)
        assert(irr > 0.0) { "irr was $irr" }
    }

    @Test
    fun `xirr of one year at ten percent`() {
        val irr = xirr(listOf(flow("2019-01-01", "-1000"), flow("2020-01-01", "1100")))
        assertEquals(0.10, assertNotNull(irr), 1e-9)
    }

    @Test
    fun `xirr matches the reference spreadsheet example`() {
        val irr =
            xirr(
                listOf(
                    flow("2008-01-01", "-10000"),
                    flow("2008-03-01", "2750"),
                    flow("2008-10-30", "4250"),
                    flow("2009-02-15", "3250"),
                    flow("2009-04-01", "2750"),
                ),
            )
        assertEquals(0.373362535, assertNotNull(irr), 1e-6)
    }

    @Test
    fun `xirr is undefined when the npv curve has two roots`() {
        // +100, -230, +132 at yearly steps has roots at 10% and 20%.
        val flows = listOf(flow("2019-01-01", "100"), flow("2020-01-01", "-230"), flow("2020-12-31", "132"))
        assertNull(xirr(flows))
    }

    @Test
    fun `nothing called means every ratio is undefined, not zero`() {
        val report = performance(fund.copy(flows = listOf(flow("2021-06-30", "10"))))

        assertNull(report.dpi)
        assertNull(report.tvpi)
        assertNull(report.irr)
    }

    @Test
    fun `a flow after the valuation date is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            fund.copy(valuationDate = LocalDate.parse("2021-06-30"))
        }
    }

    @Test
    fun `ks-pme equals tvpi against a flat benchmark`() {
        val flat = (fund.flows.map { it.date } + fund.valuationDate).associateWith { BigDecimal("100") }
        assertDecimal("1.2", ksPme(fund, flat))
    }

    @Test
    fun `ks-pme and direct alpha scale contributions by benchmark growth`() {
        val series =
            CashFlowSeries(
                currency = USD,
                flows = listOf(flow("2019-01-01", "-100")),
                nav = BigDecimal("300"),
                valuationDate = LocalDate.parse("2020-01-01"),
            )
        val benchmark = mapOf(LocalDate.parse("2019-01-01") to BigDecimal("100"), LocalDate.parse("2020-01-01") to BigDecimal("200"))

        // Contribution grows to 200 in benchmark terms, so 300 / 200.
        assertDecimal("1.5", ksPme(series, benchmark))
        assertEquals(0.5, assertNotNull(directAlpha(series, benchmark)), 1e-9)
    }

    @Test
    fun `a missing benchmark level makes pme undefined`() {
        val partial = mapOf(fund.valuationDate to BigDecimal("100"))
        assertNull(ksPme(fund, partial))
        assertNull(directAlpha(fund, partial))
    }

    @Test
    fun `commitment status follows methodology 2_3`() {
        val status = commitmentStatus(BigDecimal("200"), fund)

        assertDecimal("150", status.called)
        assertDecimal("50", status.unfunded)
        assertDecimal("0.75", status.drawdownRate)
        assertDecimal("0.4", status.distributionRate)
    }
}
