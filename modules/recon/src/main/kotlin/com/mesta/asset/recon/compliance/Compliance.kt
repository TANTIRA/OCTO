package com.mesta.asset.recon.compliance

import com.mesta.asset.analytics.CoverageReport
import com.mesta.asset.lookthrough.ExposureReport
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Currency

/** Rule set every evaluation below is computed under (#106, #6 slice 8). */
const val COMPLIANCE_RULESET = "post-trade compliance rules v1"

private val MC = MathContext.DECIMAL64

/** The checks of the first rule set. Each states its limit; the evaluator invents no threshold. */
sealed interface ComplianceCheck {
    /** No single terminal holding may exceed [maxFraction] of gross look-through exposure. */
    data class ConcentrationLimit(
        val maxFraction: BigDecimal,
    ) : ComplianceCheck

    /** Exposure in [currency] may not exceed [maxFraction] of total absolute currency exposure. */
    data class CurrencyExposureLimit(
        val currency: Currency,
        val maxFraction: BigDecimal,
    ) : ComplianceCheck

    /** The §7.3 coverage ratio may not fall below [minRatio]. */
    data class CoverageFloor(
        val minRatio: BigDecimal,
    ) : ComplianceCheck
}

/** A versioned rule (AGENTS.md: definitions in Git, not only a dashboard). A change is a new [version]. */
data class ComplianceRule(
    val id: String,
    val version: Int,
    val name: String,
    val check: ComplianceCheck,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "a rule has an id and a name" }
        require(version >= 1) { "versions start at 1" }
        val limit =
            when (check) {
                is ComplianceCheck.ConcentrationLimit -> check.maxFraction
                is ComplianceCheck.CurrencyExposureLimit -> check.maxFraction
                is ComplianceCheck.CoverageFloor -> check.minRatio
            }
        require(limit.signum() >= 0) { "$id: a limit must not be negative" }
    }
}

/**
 * What the rules are evaluated against, as computed elsewhere: the look-through exposure of the subject (#10),
 * its exposure by currency, and its coverage report (#45). A null input makes every rule that needs it
 * [Result.NOT_EVALUABLE]: missing data never passes a check (§10.7).
 */
data class ComplianceInputs(
    val subject: String,
    val asOf: LocalDate,
    val exposure: ExposureReport? = null,
    val currencyExposure: Map<Currency, BigDecimal>? = null,
    val coverage: CoverageReport? = null,
)

enum class Result(
    val wireValue: String,
) {
    PASS("pass"),
    BREACH("breach"),
    NOT_EVALUABLE("not-evaluable"),
}

/** One rule's outcome on one subject at one date. [measured] holds the figures the outcome rests on, as decimal strings. */
data class Evaluation(
    val rule: ComplianceRule,
    val subject: String,
    val asOf: LocalDate,
    val result: Result,
    val measured: Map<String, String>,
    val explanation: String,
    val ruleset: String = COMPLIANCE_RULESET,
)

/** Evaluates every rule; the caller turns each [Result.BREACH] into a workflow task. Pure. */
fun evaluate(
    rules: List<ComplianceRule>,
    inputs: ComplianceInputs,
): List<Evaluation> = rules.map { rule -> evaluateOne(rule, inputs) }

private fun evaluateOne(
    rule: ComplianceRule,
    inputs: ComplianceInputs,
): Evaluation {
    fun outcome(
        result: Result,
        measured: Map<String, BigDecimal?>,
        explanation: String,
    ) = Evaluation(rule, inputs.subject, inputs.asOf, result, measured.mapValues { it.value?.toPlainString() ?: "undefined" }, explanation)

    return when (val check = rule.check) {
        is ComplianceCheck.ConcentrationLimit -> {
            val exposure =
                inputs.exposure ?: return outcome(Result.NOT_EVALUABLE, emptyMap(), "no look-through exposure for ${inputs.subject}")
            val gross = exposure.grossExposure
            if (gross.signum() == 0) return outcome(Result.NOT_EVALUABLE, mapOf("grossExposure" to gross), "gross exposure is zero")
            val (asset, amount) = exposure.byAsset.maxBy { it.value.abs() }
            val fraction = amount.abs().divide(gross, MC)
            outcome(
                if (fraction > check.maxFraction) Result.BREACH else Result.PASS,
                mapOf("fraction" to fraction, "limit" to check.maxFraction),
                "largest holding $asset is ${fraction.toPlainString()} of gross exposure, limit ${check.maxFraction.toPlainString()}",
            )
        }
        is ComplianceCheck.CurrencyExposureLimit -> {
            val byCurrency =
                inputs.currencyExposure ?: return outcome(Result.NOT_EVALUABLE, emptyMap(), "no currency exposure for ${inputs.subject}")
            val total = byCurrency.values.fold(BigDecimal.ZERO) { acc, v -> acc + v.abs() }
            if (total.signum() == 0) return outcome(Result.NOT_EVALUABLE, mapOf("total" to total), "total currency exposure is zero")
            // A currency absent from a full breakdown is a zero exposure, not a missing one.
            val fraction = (byCurrency[check.currency] ?: BigDecimal.ZERO).abs().divide(total, MC)
            outcome(
                if (fraction > check.maxFraction) Result.BREACH else Result.PASS,
                mapOf("fraction" to fraction, "limit" to check.maxFraction),
                "${check.currency} is ${fraction.toPlainString()} of currency exposure, limit ${check.maxFraction.toPlainString()}",
            )
        }
        is ComplianceCheck.CoverageFloor -> {
            val coverage = inputs.coverage ?: return outcome(Result.NOT_EVALUABLE, emptyMap(), "no coverage report for ${inputs.subject}")
            val ratio =
                coverage.ratio
                    ?: return outcome(
                        Result.NOT_EVALUABLE,
                        mapOf("ratio" to null),
                        "coverage ratio is undefined in scenario ${coverage.scenario}",
                    )
            outcome(
                if (ratio < check.minRatio) Result.BREACH else Result.PASS,
                mapOf("ratio" to ratio, "floor" to check.minRatio),
                "coverage ratio ${ratio.toPlainString()} in scenario ${coverage.scenario}, floor ${check.minRatio.toPlainString()}",
            )
        }
    }
}
