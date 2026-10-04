package com.octo.api.agents

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.analytics.BridgeDriver
import com.octo.analytics.BridgeMethod
import com.octo.analytics.BridgePoint
import com.octo.analytics.ValueBridge
import com.octo.analytics.valueBridge
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.isBoundedObject
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/**
 * Platform callers for the three sealed narrators that the sidecar already serves and nothing
 * else invoked: equity-bridge (F6), ddq-response (F10), and operating-review (F13).
 *
 * Each route is a member action on the caller's own tenant. The equity bridge is computed here
 * with [valueBridge] and only then narrated — the sidecar does not recompute it. DDQ and the
 * operating review forward the supplied facts as the closed evidence set those workflows judge.
 * Viewers and outsiders are 404. A sidecar that is down or flag-off answers 503; any other
 * sidecar failure answers 502.
 */
@RestController
class NarratedWorkflowController(
    private val agents: AgentsClient,
    private val tenants: TenantDirectory,
    private val json: ObjectMapper,
) {
    @PostMapping("/api/v1/analytics/equity-bridge")
    fun equityBridge(
        @Valid @RequestBody body: EquityBridgeBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        if (!member(jwt, body.tenantId)) return ResponseEntity.notFound().build()
        val company = body.company.trim()
        if (!company.isRunSubject()) return ResponseEntity.badRequest().build()
        val bridge = body.toBridge() ?: return ResponseEntity.badRequest().build()
        val recorded = bridge.recorded(company)
        if (!json.isBoundedObject(recorded)) return ResponseEntity.badRequest().build()
        val narrative =
            try {
                agents.run("equity-bridge", recorded.withRun(body.tenantId))
            } catch (e: AgentsUnavailableException) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            } catch (e: AgentsCallException) {
                return agentFailure(e)
            }
        return ResponseEntity.ok(
            narrative +
                mapOf(
                    "computed" to
                        mapOf(
                            "change" to recorded.getValue("change"),
                            "effects" to recorded.getValue("effects"),
                            "method" to recorded.getValue("method"),
                            "methodology" to bridge.methodology,
                        ),
                ),
        )
    }

    /**
     * `POST /api/v1/fundraising/ddq-response` — F10. The subject is a questionnaire id, not an LP
     * name; firm facts arrive inline and the drafter has no tools.
     */
    @PostMapping("/api/v1/fundraising/ddq-response")
    fun ddqResponse(
        @Valid @RequestBody body: DdqBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        if (!member(jwt, body.tenantId)) return ResponseEntity.notFound().build()
        val subject = body.subject.trim()
        if (!subject.isRunSubject() || !body.questions.fits(QUESTION_LIMIT, QUESTION_TEXT_LIMIT)) {
            return ResponseEntity.badRequest().build()
        }
        if (!json.isBoundedObject(body.facts)) return ResponseEntity.badRequest().build()
        val recorded: Map<String, Any> =
            mapOf(
                "subject" to subject,
                "questions" to body.questions,
                "facts" to body.facts,
            )
        if (!json.isBoundedObject(recorded)) return ResponseEntity.badRequest().build()
        return call("ddq-response", recorded.withRun(body.tenantId))
    }

    /** `POST /api/v1/portfolio/operating-review` — F13. Period metrics are the whole evidence base. */
    @PostMapping("/api/v1/portfolio/operating-review")
    fun operatingReview(
        @Valid @RequestBody body: OperatingReviewBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        if (!member(jwt, body.tenantId)) return ResponseEntity.notFound().build()
        val company = body.company.trim()
        if (!company.isRunSubject() || !body.levers.fits(LEVER_LIMIT, LEVER_TEXT_LIMIT)) {
            return ResponseEntity.badRequest().build()
        }
        if (body.metrics.isEmpty() || !json.isBoundedObject(body.metrics)) return ResponseEntity.badRequest().build()
        val recorded: Map<String, Any> =
            mapOf(
                "company" to company,
                "levers" to body.levers,
                "metrics" to body.metrics,
            )
        if (!json.isBoundedObject(recorded)) return ResponseEntity.badRequest().build()
        return call("operating-review", recorded.withRun(body.tenantId))
    }

    private fun member(
        jwt: Jwt,
        tenantId: UUID,
    ): Boolean {
        val userId = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull() ?: return false
        val role = tenants.tenantsOf(userId).firstOrNull { it.tenantId == tenantId }?.role ?: return false
        return role != TenantRole.VIEWER
    }

    private fun call(
        workflow: String,
        payload: Map<String, Any>,
    ): ResponseEntity<Any> =
        try {
            ResponseEntity.ok(agents.run(workflow, payload))
        } catch (e: AgentsUnavailableException) {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        } catch (e: AgentsCallException) {
            agentFailure(e)
        }

    private fun agentFailure(e: AgentsCallException): ResponseEntity<Any> =
        ResponseEntity
            .status(if (e.statusCode == 503) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY)
            .build()

    private fun String.isRunSubject() = isNotBlank() && length <= AgentRunsController.MAX_SUBJECT_ID_LENGTH && none { it.isISOControl() }

    /** At least one non-blank item, each within [textLimit], and no more than [countLimit] items. */
    private fun List<String>.fits(
        countLimit: Int,
        textLimit: Int,
    ) = isNotEmpty() && size <= countLimit && none { it.length > textLimit } && any { it.isNotBlank() }

    private fun Map<String, Any>.withRun(tenantId: UUID): Map<String, Any> =
        this + mapOf("tenant_id" to tenantId.toString(), "run_key" to UUID.randomUUID().toString())

    data class PointBody(
        val date: LocalDate,
        val revenue: BigDecimal,
        val margin: BigDecimal,
        val multiple: BigDecimal,
        val netDebt: BigDecimal,
        val fxRate: BigDecimal,
    ) {
        fun toPoint() = BridgePoint(date, revenue, margin, multiple, netDebt, fxRate)
    }

    data class EquityBridgeBody(
        val tenantId: UUID,
        @field:NotBlank @field:Size(max = AgentRunsController.MAX_SUBJECT_ID_LENGTH) val company: String,
        @field:NotBlank val localCurrency: String,
        @field:NotBlank val reportingCurrency: String,
        @field:NotBlank val method: String,
        val ordering: List<String> = emptyList(),
        @field:Valid val entry: PointBody,
        @field:Valid val exit: PointBody,
    ) {
        /** The platform's bridge, or null when the request cannot be a §4.3 bridge. */
        fun toBridge(): ValueBridge? =
            runCatching {
                val attribution =
                    when (method) {
                        "shapley" -> BridgeMethod.Shapley
                        "sequential" -> BridgeMethod.Sequential(ordering.map { BridgeDriver.valueOf(it) })
                        else -> throw IllegalArgumentException("method")
                    }
                valueBridge(
                    entry.toPoint(),
                    exit.toPoint(),
                    Currency.getInstance(localCurrency.trim().uppercase()),
                    Currency.getInstance(reportingCurrency.trim().uppercase()),
                    attribution,
                )
            }.getOrNull()
    }

    data class DdqBody(
        val tenantId: UUID,
        @field:NotBlank @field:Size(max = AgentRunsController.MAX_SUBJECT_ID_LENGTH) val subject: String,
        val questions: List<String>,
        val facts: Map<String, Any>,
    )

    data class OperatingReviewBody(
        val tenantId: UUID,
        @field:NotBlank @field:Size(max = AgentRunsController.MAX_SUBJECT_ID_LENGTH) val company: String,
        val levers: List<String>,
        val metrics: Map<String, Any>,
    )

    private companion object {
        const val QUESTION_LIMIT = 40
        const val QUESTION_TEXT_LIMIT = 2_000
        const val LEVER_LIMIT = 20
        const val LEVER_TEXT_LIMIT = 200
    }
}

private fun ValueBridge.recorded(company: String): Map<String, Any> {
    val effects = this.effects.mapKeys { it.key.name }.mapValues { it.value.toPlainString() }
    return mapOf(
        "company" to company,
        "entry" to entry.wire(),
        "exit" to exit.wire(),
        "effects" to effects,
        "change" to change.toPlainString(),
        "method" to method.wire(),
        "local_currency" to localCurrency.currencyCode,
        "reporting_currency" to reportingCurrency.currencyCode,
    )
}

private fun BridgePoint.wire(): Map<String, String> =
    mapOf(
        "date" to date.toString(),
        "revenue" to revenue.toPlainString(),
        "margin" to margin.toPlainString(),
        "multiple" to multiple.toPlainString(),
        "net_debt" to netDebt.toPlainString(),
        "fx_rate" to fxRate.toPlainString(),
    )

private fun BridgeMethod.wire(): String =
    when (this) {
        is BridgeMethod.Sequential -> "sequential:${ordering.joinToString(",") { it.name }}"
        BridgeMethod.Shapley -> "shapley"
    }
