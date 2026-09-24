package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Currency

/** Formula set every valuation below is computed under (quantitative-methodology.md §5.1–5.2, §10.5). */
const val VALUATION_METHODOLOGY = "quantitative-methodology §5.1–5.2 v1"

private val MC = MathContext.DECIMAL64

/** §5.2: R_e = R_f + β_L(R_m − R_f) + size, country and company-specific premia. */
fun costOfEquity(
    riskFree: BigDecimal,
    leveredBeta: BigDecimal,
    marketReturn: BigDecimal,
    sizePremium: BigDecimal = BigDecimal.ZERO,
    countryPremium: BigDecimal = BigDecimal.ZERO,
    specificPremium: BigDecimal = BigDecimal.ZERO,
): BigDecimal = riskFree + leveredBeta * (marketReturn - riskFree) + sizePremium + countryPremium + specificPremium

/** §5.2: E/(D + E)·R_e + D/(D + E)·R_d·(1 − T_c), with [equity] and [debt] at market value. */
fun wacc(
    equity: BigDecimal,
    debt: BigDecimal,
    costOfEquity: BigDecimal,
    costOfDebt: BigDecimal,
    taxRate: BigDecimal,
): BigDecimal {
    require(equity.signum() >= 0 && debt.signum() >= 0) { "equity and debt must not be negative" }
    require((equity + debt).signum() > 0) { "equity and debt cannot both be zero" }
    require(taxRate.signum() >= 0 && taxRate <= BigDecimal.ONE) { "tax rate must be between 0 and 1" }
    return (equity * costOfEquity + debt * costOfDebt * (BigDecimal.ONE - taxRate)).divide(equity + debt, MC)
}

/**
 * Inputs to a §5.1 valuation, in one currency. [freeCashFlows] are FCFF_1..FCFF_N at the end of each year after
 * [valuationDate]; [terminalCashFlow] is FCFF_{N+1}.
 */
data class DcfInputs(
    val valuationDate: LocalDate,
    val currency: Currency,
    val freeCashFlows: List<BigDecimal>,
    val terminalCashFlow: BigDecimal,
    val wacc: BigDecimal,
    val terminalGrowth: BigDecimal,
    val netDebt: BigDecimal,
    val preferredClaims: BigDecimal = BigDecimal.ZERO,
    val nonOperatingAssets: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(freeCashFlows.isNotEmpty()) { "at least one year of free cash flow is required" }
        require(preferredClaims.signum() >= 0) { "preferred claims must not be negative: they are deducted from equity" }
    }
}

/** A §5.1 valuation with the inputs it came from (§10.6) and each of its components. */
data class DcfValuation(
    val inputs: DcfInputs,
    val presentValueOfCashFlows: BigDecimal,
    val terminalValue: BigDecimal,
    val presentValueOfTerminalValue: BigDecimal,
    val enterpriseValue: BigDecimal,
    val equityValue: BigDecimal,
    val methodology: String = VALUATION_METHODOLOGY,
)

/**
 * §5.1 with end-of-year discounting: EV = Σ FCFF_t/(1 + WACC)^t + TV_N/(1 + WACC)^N, TV_N = FCFF_{N+1}/(WACC − g),
 * and equity = EV − net debt − preferred claims + non-operating assets. WACC must exceed g, or the perpetuity
 * has no finite value.
 */
fun dcf(inputs: DcfInputs): DcfValuation {
    require(inputs.wacc > BigDecimal.ONE.negate()) { "WACC must be above −100%" }
    require(inputs.wacc > inputs.terminalGrowth) { "WACC must exceed terminal growth, or the terminal value is undefined" }
    val factor = BigDecimal.ONE + inputs.wacc
    val cashFlows = inputs.freeCashFlows.withIndex().sumOf { (i, cash) -> cash.divide(factor.pow(i + 1, MC), MC) }
    val terminal = inputs.terminalCashFlow.divide(inputs.wacc - inputs.terminalGrowth, MC)
    val discountedTerminal = terminal.divide(factor.pow(inputs.freeCashFlows.size, MC), MC)
    val enterprise = cashFlows + discountedTerminal
    return DcfValuation(
        inputs = inputs,
        presentValueOfCashFlows = cashFlows,
        terminalValue = terminal,
        presentValueOfTerminalValue = discountedTerminal,
        enterpriseValue = enterprise,
        equityValue = enterprise - inputs.netDebt - inputs.preferredClaims + inputs.nonOperatingAssets,
    )
}

/**
 * A WACC × g scenario grid (§10.9): [equityValues] has one row per entry of [waccs] and one column per entry of
 * [growths]. A cell where WACC does not exceed g is null: it is undefined, not invalid (§10.7).
 */
data class DcfSensitivity(
    val waccs: List<BigDecimal>,
    val growths: List<BigDecimal>,
    val equityValues: List<List<BigDecimal?>>,
) {
    init {
        require(waccs.isNotEmpty() && growths.isNotEmpty()) { "the grid needs at least one WACC and one growth rate" }
        require(equityValues.size == waccs.size && equityValues.all { it.size == growths.size }) {
            "equityValues must be a ${waccs.size}×${growths.size} grid"
        }
    }
}

/** §10.9 scenarios, holding the declared FCFF_{N+1}: the equity value for every [waccs] × [growths] pair. */
fun dcfSensitivity(
    inputs: DcfInputs,
    waccs: List<BigDecimal>,
    growths: List<BigDecimal>,
): DcfSensitivity =
    DcfSensitivity(
        waccs,
        growths,
        waccs.map { w -> growths.map { g -> if (w > g) dcf(inputs.copy(wacc = w, terminalGrowth = g)).equityValue else null } },
    )
