package com.mesta.asset.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Currency

/** Formula set every LBO result below is computed under (quantitative-methodology.md §5.4, §10.5). */
const val LBO_METHODOLOGY = "quantitative-methodology §5.4 v1"

private val MC = MathContext.DECIMAL64

/**
 * A leveraged buyout from the sponsor's side, in one currency. [interimDistributions] are dividends or recaps
 * paid to the sponsor after entry and no later than exit; [exitMetric] × [exitMultiple] is the exit EV.
 */
data class LboInputs(
    val currency: Currency,
    val entryDate: LocalDate,
    val initialSponsorEquity: BigDecimal,
    val exitDate: LocalDate,
    val exitMetric: BigDecimal,
    val exitMultiple: BigDecimal,
    val exitNetDebt: BigDecimal,
    val interimDistributions: List<CashFlow> = emptyList(),
) {
    init {
        require(initialSponsorEquity.signum() > 0) { "initial sponsor equity must be positive" }
        require(entryDate.isBefore(exitDate)) { "exit must come after entry" }
        require(exitMultiple.signum() >= 0) { "exit multiple must not be negative" }
        require(interimDistributions.all { it.amount.signum() > 0 }) { "an interim distribution must be positive" }
        require(interimDistributions.all { it.date.isAfter(entryDate) && !it.date.isAfter(exitDate) }) {
            "interim distributions must fall after entry and no later than exit"
        }
    }
}

/**
 * §5.4 results with the inputs they came from (§10.6). [exitEquity] is ExitEV − ExitNetDebt exactly as §5.4 writes
 * it and can be negative; [exitProceeds] floors it at zero, because a limited-liability sponsor cannot receive less
 * than nothing, and MOIC and IRR are computed on the proceeds. [irr] is null where §2.1 has no single root.
 */
data class LboReturns(
    val inputs: LboInputs,
    val exitEnterpriseValue: BigDecimal,
    val exitEquity: BigDecimal,
    val exitProceeds: BigDecimal,
    val moic: BigDecimal,
    val irr: Double?,
    val methodology: String = LBO_METHODOLOGY,
)

/** SponsorMOIC = (exit proceeds + interim distributions) / initial sponsor equity; IRR from the dated flows (§2.1). */
fun lboReturns(inputs: LboInputs): LboReturns {
    val exitEnterpriseValue = inputs.exitMetric * inputs.exitMultiple
    val exitEquity = exitEnterpriseValue - inputs.exitNetDebt
    val exitProceeds = exitEquity.max(BigDecimal.ZERO)
    val distributed = inputs.interimDistributions.fold(BigDecimal.ZERO) { total, flow -> total + flow.amount }
    val flows =
        listOf(CashFlow(inputs.entryDate, inputs.initialSponsorEquity.negate())) +
            inputs.interimDistributions +
            CashFlow(inputs.exitDate, exitProceeds)
    return LboReturns(
        inputs = inputs,
        exitEnterpriseValue = exitEnterpriseValue,
        exitEquity = exitEquity,
        exitProceeds = exitProceeds,
        moic = (exitProceeds + distributed).divide(inputs.initialSponsorEquity, MC),
        irr = xirr(flows),
    )
}
