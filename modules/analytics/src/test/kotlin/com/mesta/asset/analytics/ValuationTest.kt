package com.mesta.asset.analytics

import java.math.BigDecimal
import java.util.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val USD = Currency.getInstance("USD")

private fun bd(value: String) = BigDecimal(value)

private fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
) {
    assertNotNull(actual)
    assertEquals(0, bd(expected).compareTo(actual), "expected $expected but was $actual")
}

class ValuationTest {
    @Test
    fun `cost of equity follows methodology 5_2`() {
        // 3% + 1.5·(8% − 3%) + 1% + 0.5% + 0 = 12%.
        val re =
            costOfEquity(
                riskFree = bd("0.03"),
                leveredBeta = bd("1.5"),
                marketReturn = bd("0.08"),
                sizePremium = bd("0.01"),
                countryPremium = bd("0.005"),
            )
        assertDecimal("0.12", re)
    }

    @Test
    fun `wacc from a hand-worked cost of equity`() {
        // R_e = 12%, R_d = 6%, T_c = 25%, E/(D+E) = 60/40 → 0.6·12% + 0.4·6%·75% = 9%.
        val w = wacc(bd("60"), bd("40"), bd("0.12"), bd("0.06"), bd("0.25"))
        assertDecimal("0.09", w)
    }

    @Test
    fun `wacc rejects a negative side, a zero total, and a tax rate outside 0 to 1`() {
        assertFailsWith<IllegalArgumentException> { wacc(bd("-1"), bd("40"), bd("0.12"), bd("0.06"), bd("0.25")) }
        assertFailsWith<IllegalArgumentException> { wacc(bd("60"), bd("-40"), bd("0.12"), bd("0.06"), bd("0.25")) }
        assertFailsWith<IllegalArgumentException> { wacc(bd("0"), bd("0"), bd("0.12"), bd("0.06"), bd("0.25")) }
        assertFailsWith<IllegalArgumentException> { wacc(bd("60"), bd("40"), bd("0.12"), bd("0.06"), bd("1.01")) }
        assertFailsWith<IllegalArgumentException> { wacc(bd("60"), bd("40"), bd("0.12"), bd("0.06"), bd("-0.01")) }
    }

    /**
     * Hand-worked case, exact in decimal: WACC 25%, g 5%, N = 3. FCFFs grow so each PV is 100;
     * FCFF_4 = 200, so TV = 200/0.20 = 1000 and its PV is 1000/1.25³ = 512.
     */
    private fun handWorkedInputs() =
        DcfInputs(
            currency = USD,
            freeCashFlows = listOf(bd("125"), bd("156.25"), bd("195.3125")),
            terminalFreeCashFlow = bd("200"),
            wacc = bd("0.25"),
            terminalGrowth = bd("0.05"),
            netDebt = bd("100"),
            preferredClaims = bd("12"),
            nonOperatingAssets = bd("50"),
        )

    @Test
    fun `dcf reproduces the hand-worked case`() {
        val v = dcf(handWorkedInputs())

        assertDecimal("300", v.pvOfCashFlows) // 100 + 100 + 100
        assertDecimal("1000", v.terminalValue) // 200 / (0.25 − 0.05)
        assertDecimal("512", v.pvOfTerminalValue) // 1000 / 1.25³
        assertDecimal("812", v.enterpriseValue)
        assertDecimal("750", v.equityValue) // 812 − 100 − 12 + 50
        assertEquals(VALUATION_METHODOLOGY, v.methodology)
        assertEquals(bd("0.25"), v.inputs.wacc)
    }

    @Test
    fun `dcf requires WACC above terminal growth`() {
        assertFailsWith<IllegalArgumentException> { dcf(handWorkedInputs().copy(terminalGrowth = bd("0.25"))) }
        assertFailsWith<IllegalArgumentException> { dcf(handWorkedInputs().copy(terminalGrowth = bd("0.30"))) }
    }

    @Test
    fun `dcf rejects an empty forecast and negative preferred claims`() {
        assertFailsWith<IllegalArgumentException> { handWorkedInputs().copy(freeCashFlows = emptyList()) }
        assertFailsWith<IllegalArgumentException> { handWorkedInputs().copy(preferredClaims = bd("-1")) }
    }

    @Test
    fun `sensitivity reproduces the point estimate and stays undefined where WACC is at or below g`() {
        // Asymmetric axes pin the orientation: rows are WACCs, columns are growths.
        val grid =
            dcfSensitivity(
                handWorkedInputs(),
                waccs = listOf(bd("0.20"), bd("0.25")),
                growths = listOf(bd("0.03"), bd("0.05"), bd("0.22")),
            )

        assertEquals(2, grid.equityValues.size)
        assertEquals(3, grid.equityValues[0].size)

        // The base cell reproduces the point estimate exactly.
        assertEquals(0, dcf(handWorkedInputs()).equityValue.compareTo(grid.equityValues[1][1]))

        // A non-base cell matches a direct valuation at that pair; a transposed grid would not.
        assertEquals(
            0,
            dcf(handWorkedInputs().copy(wacc = bd("0.20"), terminalGrowth = bd("0.03")))
                .equityValue
                .compareTo(grid.equityValues[0][0]),
        )

        assertNull(grid.equityValues[0][2]) // WACC 0.20 ≤ g 0.22
        assertNotNull(grid.equityValues[1][2]) // WACC 0.25 > g 0.22

        // Equity value falls as WACC rises along the same growth column.
        val column = grid.equityValues.map { it[0]!! }
        assertEquals(listOf(true), column.zipWithNext().map { (a, b) -> a > b })
    }

    @Test
    fun `sensitivity rejects an empty axis`() {
        assertFailsWith<IllegalArgumentException> {
            dcfSensitivity(handWorkedInputs(), waccs = emptyList(), growths = listOf(bd("0.05")))
        }
    }
}
