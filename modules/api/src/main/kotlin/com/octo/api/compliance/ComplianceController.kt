package com.octo.api.compliance

import com.octo.analytics.CoverageReport
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.agents.AgentRunsController
import com.octo.api.agents.AgentsCallException
import com.octo.api.agents.AgentsClient
import com.octo.api.agents.AgentsUnavailableException
import com.octo.lookthrough.ExposureReport
import com.octo.persistence.TenantScope
import com.octo.recon.compliance.ComplianceCheck
import com.octo.recon.compliance.ComplianceInputs
import com.octo.recon.compliance.ComplianceRule
import com.octo.recon.compliance.persistence.ComplianceProvenance
import com.octo.recon.compliance.persistence.ComplianceStore
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

internal const val SUBJECT_LIMIT = 300

/**
 * Mirrors `compliance_rule_id_shape` in V14 so a malformed id is answered 400 at the edge.
 * The CHECK constraint stays the authority; this only keeps its violation out of the 500 path.
 */
private val RULE_ID_SHAPE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

/**
 * Post-trade compliance (#6 slice 8). Rules are governance, so defining one needs an `approver` or `admin`;
 * running an evaluation needs a working role; listing needs any role. Outside the tenant everything is 404.
 */
@RestController
class ComplianceController(
    private val store: ComplianceStore,
    private val runner: ComplianceRunner,
    private val tenants: TenantDirectory,
    private val agents: AgentsClient,
) {
    @PostMapping("/api/v1/compliance/rules")
    fun defineRule(
        @Valid @RequestBody body: RuleBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RuleView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role != TenantRole.APPROVER && role != TenantRole.ADMIN) return ResponseEntity.notFound().build()
        if (!RULE_ID_SHAPE.matches(body.ruleId)) return ResponseEntity.badRequest().build()
        val check =
            runCatching { body.check.toCheck() }
                .getOrElse { return ResponseEntity.badRequest().build() }
        val rule =
            try {
                store.defineRule(
                    body.tenantId,
                    body.ruleId,
                    body.name,
                    check,
                    body.version,
                    ComplianceProvenance(jwt.subject!!, UUID.randomUUID()),
                    TenantScope.User(userId),
                )
            } catch (e: IllegalStateException) {
                // A caller-supplied version that is not the next one is a write conflict, not a bad request.
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().build()
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(rule.view())
    }

    /**
     * `POST /api/v1/compliance/rules/{ruleId}/retire` — takes a rule out of the active set the
     * append-only way: a new version marked inactive (#266). The definition that governed past
     * evaluations stays auditable, a later define re-activates the rule, and an unknown rule
     * answers 404 like every other miss here.
     */
    @PostMapping("/api/v1/compliance/rules/{ruleId}/retire")
    fun retireRule(
        @PathVariable ruleId: String,
        @RequestBody body: RetireBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RuleView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role != TenantRole.APPROVER && role != TenantRole.ADMIN) return ResponseEntity.notFound().build()
        if (!RULE_ID_SHAPE.matches(ruleId)) return ResponseEntity.badRequest().build()
        val rule =
            store.retire(
                body.tenantId,
                ruleId,
                ComplianceProvenance(jwt.subject!!, body.correlationId ?: UUID.randomUUID()),
                TenantScope.User(userId),
            ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(rule.view())
    }

    @GetMapping("/api/v1/compliance/rules")
    fun rules(
        @RequestParam tenantId: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<RuleView>> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        roleIn(userId, tenantId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(store.activeRules(tenantId, TenantScope.User(userId)).map { it.view() })
    }

    @PostMapping("/api/v1/compliance/evaluations")
    fun evaluate(
        @Valid @RequestBody body: EvaluationBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<OutcomeView>> {
        val role =
            roleIn(userId(jwt) ?: return ResponseEntity.notFound().build(), body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val inputs = body.inputs() ?: return ResponseEntity.badRequest().build()
        val outcomes =
            try {
                runner.run(body.tenantId, inputs, jwt.subject!!, UUID.randomUUID())
            } catch (e: IllegalStateException) {
                // More active rules than a run can cover is the tenant's state, not the request's shape.
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        return ResponseEntity.ok(outcomes.map { it.view() })
    }

    /**
     * `POST /api/v1/compliance/rationale` (F9) — same inputs as `/evaluations`: the deterministic
     * engine runs (its breach task dedupe makes that safe), then the sidecar narrates the outcomes
     * into an approver-readable rationale under the citation gate. The run lands on `agent_run`;
     * `status` is `completed` only when jev verifies every cited figure. Same auth and sidecar
     * error contract as the other agent triggers.
     */
    @PostMapping("/api/v1/compliance/rationale")
    fun rationale(
        @Valid @RequestBody body: EvaluationBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val inputs = body.inputs() ?: return ResponseEntity.badRequest().build()
        // The sidecar records the run under subject_id "{subject}/{as_of}"; reject what the run
        // spine would refuse before the engine records outcomes and opens breach tasks (#501).
        if ("${body.subject}/${body.asOf}".length > AgentRunsController.MAX_SUBJECT_ID_LENGTH) {
            return ResponseEntity.badRequest().build()
        }
        val outcomes =
            try {
                runner.run(body.tenantId, inputs, jwt.subject!!, UUID.randomUUID())
            } catch (e: IllegalStateException) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        val result =
            try {
                agents.run(
                    "compliance-rationale",
                    mapOf(
                        "tenant_id" to body.tenantId.toString(),
                        "subject" to body.subject,
                        "as_of" to body.asOf.toString(),
                        "outcomes" to outcomes.map { it.wire() },
                        "run_key" to UUID.randomUUID().toString(),
                    ),
                )
            } catch (e: AgentsUnavailableException) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            } catch (e: AgentsCallException) {
                return ResponseEntity
                    .status(
                        if (e.statusCode == 503) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY,
                    ).build()
            }
        return ResponseEntity.ok(result)
    }

    private fun roleIn(
        userId: UUID,
        tenantId: UUID,
    ): TenantRole? = tenants.tenantsOf(userId).firstOrNull { it.tenantId == tenantId }?.role

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()

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

    /**
     * `version` is an optimistic-concurrency hint: supplied it must equal the version the
     * definition lands at (409 otherwise), omitted the server assigns the next one.
     */
    data class RuleBody(
        val tenantId: UUID,
        @field:NotBlank val ruleId: String,
        val version: Int? = null,
        @field:NotBlank @field:Size(max = SUBJECT_LIMIT) val name: String,
        @field:Valid val check: CheckBody,
    )

    data class RetireBody(
        val tenantId: UUID,
        val correlationId: UUID? = null,
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
    ) {
        /** The engine's input shape; null when a field cannot parse or the subject is oversized (callers answer 400). */
        fun inputs(): ComplianceInputs? =
            runCatching {
                require(subject.length <= SUBJECT_LIMIT) { "subject is capped at $SUBJECT_LIMIT characters" }
                ComplianceInputs(
                    subject = subject,
                    asOf = asOf,
                    exposure = exposure?.let { ExposureReport(subject, Currency.getInstance(it.currency), it.byAsset) },
                    currencyExposure = currencyExposure?.mapKeys { Currency.getInstance(it.key) },
                    coverage =
                        coverage?.let {
                            CoverageReport(asOf, asOf, Currency.getInstance(it.currency), it.scenario, null, null, null, null, it.ratio)
                        },
                )
            }.getOrNull()
    }

    data class OutcomeView(
        val ruleId: String,
        val version: Int,
        val result: String,
        val measured: Map<String, String>,
        val explanation: String,
        val taskId: UUID?,
        val recorded: Boolean,
    )

    private fun Outcome.view() =
        OutcomeView(
            evaluation.rule.id,
            evaluation.rule.version,
            evaluation.result.wireValue,
            evaluation.measured,
            evaluation.explanation,
            taskId,
            recorded,
        )

    /** The shape the sidecar narrates — only what the engine produced, nothing more. */
    private fun Outcome.wire() =
        mapOf(
            "rule_id" to evaluation.rule.id,
            "version" to evaluation.rule.version.toString(),
            "result" to evaluation.result.wireValue,
            "measured" to evaluation.measured,
            "explanation" to evaluation.explanation,
            "task_id" to (taskId?.toString() ?: ""),
            "recorded" to recorded.toString(),
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
