package com.mesta.asset.api.compliance

import com.mesta.asset.analytics.CoverageReport
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.lookthrough.ExposureReport
import com.mesta.asset.recon.compliance.ComplianceCheck
import com.mesta.asset.recon.compliance.ComplianceInputs
import com.mesta.asset.recon.compliance.ComplianceRule
import com.mesta.asset.recon.compliance.persistence.ComplianceProvenance
import com.mesta.asset.recon.compliance.persistence.ComplianceStore
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/**
 * Post-trade compliance (#6 slice 8). Rules are governance, so defining one needs an `approver` or `admin`;
 * running an evaluation needs a working role; listing needs any role. Outside the tenant everything is 404.
 */
@RestController
class ComplianceController(
    private val store: ComplianceStore,
    private val runner: ComplianceRunner,
    private val tenants: TenantDirectory,
) {
    @PostMapping("/api/v1/compliance/rules")
    fun defineRule(
        @Valid @RequestBody body: RuleBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RuleView> {
        val role = roleIn(jwt, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role != TenantRole.APPROVER && role != TenantRole.ADMIN) return ResponseEntity.notFound().build()
        val rule =
            runCatching {
                ComplianceRule(body.ruleId, body.version, body.name, body.check.toCheck())
            }.getOrElse { return ResponseEntity.badRequest().build() }
        store.defineRule(body.tenantId, rule, ComplianceProvenance(jwt.subject, UUID.randomUUID()))
        return ResponseEntity.status(HttpStatus.CREATED).body(rule.view())
    }

    @GetMapping("/api/v1/compliance/rules")
    fun rules(
        @RequestParam tenantId: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<RuleView>> {
        roleIn(jwt, tenantId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(store.activeRules(tenantId).map { it.view() })
    }

    @PostMapping("/api/v1/compliance/evaluations")
    fun evaluate(
        @Valid @RequestBody body: EvaluationBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<OutcomeView>> {
        val role = roleIn(jwt, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val inputs =
            ComplianceInputs(
                subject = body.subject,
                asOf = body.asOf,
                exposure = body.exposure?.let { ExposureReport(body.subject, Currency.getInstance(it.currency), it.byAsset) },
                currencyExposure = body.currencyExposure?.mapKeys { Currency.getInstance(it.key) },
                coverage =
                    body.coverage?.let {
                        CoverageReport(
                            body.asOf,
                            body.asOf,
                            Currency.getInstance(it.currency),
                            it.scenario,
                            null,
                            null,
                            null,
                            null,
                            it.ratio,
                        )
                    },
            )
        val outcomes = runner.run(body.tenantId, inputs, jwt.subject, UUID.randomUUID())
        return ResponseEntity.ok(
            outcomes.map {
                OutcomeView(
                    it.evaluation.rule.id,
                    it.evaluation.rule.version,
                    it.evaluation.result.wireValue,
                    it.evaluation.measured,
                    it.evaluation.explanation,
                    it.taskId,
                    it.recorded,
                )
            },
        )
    }

    private fun roleIn(
        jwt: Jwt,
        tenantId: UUID,
    ): TenantRole? {
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return null
        return tenants.tenantsOf(userId).firstOrNull { it.tenantId == tenantId }?.role
    }

    /** `type` is `concentration-limit`, `currency-exposure-limit` or `coverage-floor`; the other fields depend on it. */
    data class CheckBody(
        @field:NotBlank val type: String,
        val maxFraction: BigDecimal? = null,
        val currency: String? = null,
        val minRatio: BigDecimal? = null,
    ) {
        fun toCheck(): ComplianceCheck =
            when (type) {
                "concentration-limit" -> ComplianceCheck.ConcentrationLimit(requireNotNull(maxFraction) { "maxFraction" })
                "currency-exposure-limit" ->
                    ComplianceCheck.CurrencyExposureLimit(
                        Currency.getInstance(
                            requireNotNull(currency) {
                                "currency"
                            },
                        ),
                        requireNotNull(maxFraction) { "maxFraction" },
                    )
                "coverage-floor" -> ComplianceCheck.CoverageFloor(requireNotNull(minRatio) { "minRatio" })
                else -> throw IllegalArgumentException("unknown check type $type")
            }
    }

    data class RuleBody(
        val tenantId: UUID,
        @field:NotBlank val ruleId: String,
        val version: Int,
        @field:NotBlank val name: String,
        @field:Valid val check: CheckBody,
    )

    data class RuleView(
        val ruleId: String,
        val version: Int,
        val name: String,
        val check: CheckBody,
    )

    data class ExposureBody(
        val currency: String,
        val byAsset: Map<String, BigDecimal>,
    )

    data class CoverageBody(
        val currency: String,
        val scenario: String,
        val ratio: BigDecimal?,
    )

    data class EvaluationBody(
        val tenantId: UUID,
        @field:NotBlank val subject: String,
        val asOf: LocalDate,
        val exposure: ExposureBody? = null,
        val currencyExposure: Map<String, BigDecimal>? = null,
        val coverage: CoverageBody? = null,
    )

    data class OutcomeView(
        val ruleId: String,
        val version: Int,
        val result: String,
        val measured: Map<String, String>,
        val explanation: String,
        val taskId: UUID?,
        val recorded: Boolean,
    )

    private fun ComplianceRule.view() =
        RuleView(
            id,
            version,
            name,
            when (val c = check) {
                is ComplianceCheck.ConcentrationLimit -> CheckBody("concentration-limit", maxFraction = c.maxFraction)
                is ComplianceCheck.CurrencyExposureLimit ->
                    CheckBody(
                        "currency-exposure-limit",
                        maxFraction = c.maxFraction,
                        currency = c.currency.currencyCode,
                    )
                is ComplianceCheck.CoverageFloor -> CheckBody("coverage-floor", minRatio = c.minRatio)
            },
        )
}
