package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.util.Currency

/** Formula set every valuation below is computed under (quantitative-methodology.md §5.1–5.2, §10.5). */
const val VALUATION_METHODOLOGY = "quantitative-methodology §5.1–5.2 v1"

private val MC = MathContext.DECIMAL64

/**
 * Cost of equity (methodology §5.2):
 * R_e = R_f + β_L·(R_m − R_f) + RP_size + RP_country + RP_specific.
 * Premiums may be zero; a negative premium is allowed where the analyst intends a discount.
 */
fun costOfEquity(
    riskFree: BigDecimal,
    leveredBeta: BigDecimal,
    marketReturn: BigDecimal,
    sizePremium: BigDecimal = BigDecimal.ZERO,
    countryPremium: BigDecimal = BigDecimal.ZERO,
    specificPremium: BigDecimal = BigDecimal.ZERO,
): BigDecimal =
    riskFree +
        leveredBeta.multiply(marketReturn - riskFree, MC) +
        sizePremium +
        countryPremium +
        specificPremium

/**
 * Weighted average cost of capital (methodology §5.2):
 * WACC = E/(D+E)·R_e + D/(D+E)·R_d·(1 − T_c). Equity and debt are market values; a negative
 * value, a zero total, or a tax rate outside [0, 1] is rejected rather than averaged in.
 */
fun wacc(
    equity: BigDecimal,
    debt: BigDecimal,
    costOfEquity: BigDecimal,
    costOfDebt: BigDecimal,
    taxRate: BigDecimal,
): BigDecimal {
    require(equity.signum() >= 0) { "equity value must not be negative" }
    require(debt.signum() >= 0) { "debt value must not be negative" }
    val total = equity + debt
    require(total.signum() > 0) { "capital structure has zero total value" }
    require(taxRate >= BigDecimal.ZERO && taxRate <= BigDecimal.ONE) { "tax rate must lie in [0, 1]" }
    return equity
        .divide(total, MC)
        .multiply(costOfEquity, MC) +
        debt
            .divide(total, MC)
            .multiply(costOfDebt, MC)
            .multiply(BigDecimal.ONE - taxRate, MC)
}

/**
 * Inputs to a discounted cash flow valuation (methodology §5.1). Cash flows are FCFF for
 * years 1..N, discounted at year end as §5.1 writes it; [terminalFreeCashFlow] is FCFF_{N+1},
 * an input in its own right rather than regrown from FCFF_N (open question for the quant owner).
 */
data class DcfInputs(
    val currency: Currency,
    val freeCashFlows: List<BigDecimal>,
    val terminalFreeCashFlow: BigDecimal,
    val wacc: BigDecimal,
    val terminalGrowth: BigDecimal,
    val netDebt: BigDecimal = BigDecimal.ZERO,
    val preferredClaims: BigDecimal = BigDecimal.ZERO,
    val nonOperatingAssets: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(freeCashFlows.isNotEmpty()) { "at least one forecast year is required" }
        require(preferredClaims.signum() >= 0) { "preferred claims must not be negative" }
    }
}

/**
 * A DCF result with every intermediate component and its inputs retained, so a NAV can be
 * traced to the formula and inputs that produced it (methodology §10.5, §10.6).
 */
data class DcfValuation(
    val inputs: DcfInputs,
    val pvOfCashFlows: BigDecimal,
    val terminalValue: BigDecimal,
    val pvOfTerminalValue: BigDecimal,
    val enterpriseValue: BigDecimal,
    val equityValue: BigDecimal,
    val methodology: String = VALUATION_METHODOLOGY,
)

/**
 * Enterprise and equity value under methodology §5.1:
 * EV = Σ_{t=1..N} FCFF_t/(1+WACC)^t + TV_N/(1+WACC)^N, TV_N = FCFF_{N+1}/(WACC − g);
 * EquityValue = EV − NetDebt − PreferredClaims + NonOperatingAssets.
 * WACC must exceed g: at WACC ≤ g the terminal value is undefined, not infinite.
 */
fun dcf(inputs: DcfInputs): DcfValuation {
    val wacc = inputs.wacc
    require(wacc > inputs.terminalGrowth) { "WACC must exceed terminal growth, else the terminal value is undefined" }

    val base = BigDecimal.ONE + wacc
    val pvOfCashFlows =
        inputs.freeCashFlows.foldIndexed(BigDecimal.ZERO) { i, acc, fcff ->
            acc + fcff.divide(base.pow(i + 1), MC)
        }
    val terminalValue = inputs.terminalFreeCashFlow.divide(wacc - inputs.terminalGrowth, MC)
    val pvOfTerminalValue = terminalValue.divide(base.pow(inputs.freeCashFlows.size), MC)
    val enterpriseValue = pvOfCashFlows + pvOfTerminalValue
    val equityValue =
        enterpriseValue - inputs.netDebt - inputs.preferredClaims + inputs.nonOperatingAssets

    return DcfValuation(
        inputs = inputs,
        pvOfCashFlows = pvOfCashFlows,
        terminalValue = terminalValue,
        pvOfTerminalValue = pvOfTerminalValue,
        enterpriseValue = enterpriseValue,
        equityValue = equityValue,
    )
}

/**
 * A WACC × g scenario grid (methodology §10.9): the equity value for every pair, row-indexed by
 * [waccs] and column-indexed by [growths]. A cell where WACC ≤ g is null — undefined (§10.7).
 */
data class DcfSensitivity(
    val waccs: List<BigDecimal>,
    val growths: List<BigDecimal>,
    val equityValues: List<List<BigDecimal?>>,
) {
    init {
        require(waccs.isNotEmpty() && growths.isNotEmpty()) { "a grid needs at least one WACC and one growth rate" }
        require(equityValues.size == waccs.size && equityValues.all { it.size == growths.size }) {
            "equityValues must be a ${waccs.size}×${growths.size} grid"
        }
    }
}

fun dcfSensitivity(
    inputs: DcfInputs,
    waccs: List<BigDecimal>,
    growths: List<BigDecimal>,
): DcfSensitivity {
    val grid =
        waccs.map { w ->
            growths.map { g ->
                if (w <= g) null else dcf(inputs.copy(wacc = w, terminalGrowth = g)).equityValue
            }
        }
    return DcfSensitivity(waccs, growths, grid)
}
