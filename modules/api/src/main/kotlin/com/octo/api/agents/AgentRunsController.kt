package com.octo.api.agents

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.agents.persistence.AgentRun
import com.octo.api.agents.persistence.AgentRunRecord
import com.octo.api.agents.persistence.AgentRunStatus
import com.octo.api.agents.persistence.AgentRuns
import com.octo.persistence.TenantScope
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
import java.util.UUID

/**
 * `octo.agent_run` (V33) over HTTP — the surface the sidecar records every production run on
 * (F4). Writes carry the caller's own tenant scope: the service principal records runs for the
 * tenants it is provisioned into, and a member token does the same for user-triggered runs.
 * Unparseable input fails 400 at the boundary; a run_key replay reads the existing row back
 * rather than duplicating (a retried trigger is idempotent by construction).
 */
@RestController
class AgentRunsController(
    private val runs: AgentRuns,
    private val tenants: TenantDirectory,
    private val json: ObjectMapper,
    private val agents: AgentsClient,
) {
    private fun roleIn(
        userId: UUID,
        tenantId: UUID,
    ): TenantRole? =
        tenants
            .tenantsOf(userId)
            .firstOrNull { it.tenantId == tenantId }
            ?.role

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()

    @PostMapping("/api/v1/agent-runs")
    fun record(
        @RequestBody body: RecordRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (!body.workflow.matches(Regex("[a-z][a-z0-9-]{0,62}")) ||
            !body.subjectType.matches(Regex("[a-z][a-z0-9_-]{0,62}")) ||
            body.runKey.isBlank() || body.subjectId.isBlank() ||
            body.runKey.length > 200 || body.subjectId.length > 200
        ) {
            return ResponseEntity.badRequest().build()
        }
        val scope = TenantScope.User(userId)
        val provenance = AccessProvenance("api", body.correlationId ?: UUID.randomUUID())
        val id =
            runs
                .record(
                    AgentRunRecord(
                        tenantId = body.tenantId,
                        workflow = body.workflow,
                        runKey = body.runKey,
                        subjectType = body.subjectType,
                        subjectId = body.subjectId,
                        actor = jwt.subject!!,
                        input = json.writeValueAsString(body.input),
                        models = json.writeValueAsString(body.models),
                        thresholds = body.thresholds?.let { json.writeValueAsString(it) },
                        requestIds = body.requestIds?.let { json.writeValueAsString(it) },
                        provenance = provenance,
                    ),
                    scope,
                )
                // run_key dedupe: null means this logical run already recorded — hand the row back.
                ?: return runs.loadByKey(body.tenantId, body.runKey, scope)!!.let { ResponseEntity.ok(it.view(json)) }
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("id" to id))
    }

    @PostMapping("/api/v1/agent-runs/{id}/finish")
    fun finish(
        @PathVariable id: UUID,
        @RequestBody body: FinishRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val scope = TenantScope.User(userId)
        val run = runs.load(id, scope) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, run.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val status = body.toStatus() ?: return ResponseEntity.badRequest().build()
        if (status == AgentRunStatus.RUNNING) return ResponseEntity.badRequest().build()
        val closed =
            runs.finish(
                id,
                status,
                output = body.output?.let { json.writeValueAsString(it) },
                verdict = body.verdict?.let { json.writeValueAsString(it) },
                error = body.error,
                scope = scope,
            )
        return if (closed) {
            ResponseEntity.ok(runs.load(id, scope)!!.view(json))
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @PostMapping("/api/v1/agent-runs/{id}/outcome")
    fun outcome(
        @PathVariable id: UUID,
        @RequestBody body: OutcomeRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val scope = TenantScope.User(userId)
        val run = runs.load(id, scope) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, run.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val landed =
            runs.recordOutcome(id, json.writeValueAsString(mapOf("decided_by" to jwt.subject!!) + body.outcome), scope)
        return if (landed) {
            ResponseEntity.ok(runs.load(id, scope)!!.view(json))
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @GetMapping("/api/v1/agent-runs")
    fun list(
        @RequestParam tenantId: UUID,
        @RequestParam(required = false) subjectType: String?,
        @RequestParam(required = false) subjectId: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        roleIn(userId, tenantId) ?: return ResponseEntity.notFound().build()
        if (limit !in 1..200) return ResponseEntity.badRequest().build()
        val scope = TenantScope.User(userId)
        return ResponseEntity.ok(runs.list(tenantId, subjectType, subjectId, limit, scope).map { it.view(json) })
    }

    @GetMapping("/api/v1/agent-runs/{id}")
    fun load(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val scope = TenantScope.User(userId)
        val run = runs.load(id, scope) ?: return ResponseEntity.notFound().build()
        roleIn(userId, run.tenantId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(run.view(json))
    }

    /**
     * `POST /api/v1/agent-runs/calibration` — F12's feedback read: the sidecar joins verdict
     * against `human_outcome` across this tenant's run spine and reports agreement plus
     * eval-ready disagreement cases. Member-or-better — it reads history, and the analysis
     * itself is a recorded `calibration` run. Same 503/502 sidecar contract as the other
     * triggers.
     */
    @PostMapping("/api/v1/agent-runs/calibration")
    fun calibrate(
        @RequestBody body: CalibrationRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val result =
            try {
                agents.run(
                    "calibration",
                    mapOf(
                        "tenant_id" to body.tenantId.toString(),
                        "run_key" to "calibration:${body.tenantId}:${UUID.randomUUID()}",
                        "limit" to (body.limit ?: 200),
                    ),
                )
            } catch (e: AgentsUnavailableException) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            } catch (e: AgentsCallException) {
                return ResponseEntity
                    .status(if (e.statusCode == 503) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY)
                    .build()
            }
        return ResponseEntity.ok(result)
    }

    data class RecordRequest(
        val tenantId: UUID,
        val workflow: String,
        val runKey: String,
        val subjectType: String,
        val subjectId: String,
        val input: Any,
        val models: Any,
        val thresholds: Any? = null,
        val requestIds: Any? = null,
        val correlationId: UUID? = null,
    )

    data class FinishRequest(
        val status: String,
        val output: Any? = null,
        val verdict: Any? = null,
        val error: String? = null,
    ) {
        fun toStatus(): AgentRunStatus? = runCatching { AgentRunStatus.valueOf(status.replace('-', '_').uppercase()) }.getOrNull()
    }

    data class OutcomeRequest(
        val outcome: Map<String, Any>,
    )

    data class CalibrationRequest(
        val tenantId: UUID,
        val limit: Int? = null,
    )

    companion object {
        /** jsonb columns come back as text — re-parse so the wire shape is real JSON, not escaped. */
        private fun AgentRun.view(json: ObjectMapper) =
            mapOf(
                "id" to id,
                "tenantId" to tenantId,
                "workflow" to workflow,
                "runKey" to runKey,
                "subjectType" to subjectType,
                "subjectId" to subjectId,
                "status" to status.wireValue,
                "actor" to actor,
                "input" to json.readTree(input),
                "output" to output?.let { json.readTree(it) },
                "verdict" to verdict?.let { json.readTree(it) },
                "models" to json.readTree(models),
                "thresholds" to thresholds?.let { json.readTree(it) },
                "requestIds" to requestIds?.let { json.readTree(it) },
                "error" to error,
                "humanOutcome" to humanOutcome?.let { json.readTree(it) },
                "createdAt" to createdAt,
                "finishedAt" to finishedAt,
            )
    }
}
