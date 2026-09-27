package com.octo.api.prospect

import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.ScreeningCriteria
import com.octo.dealsourcing.ScreeningOutcome
import com.octo.dealsourcing.ScreeningVerdict
import com.octo.dealsourcing.TenantScope
import com.octo.dealsourcing.evaluateAll
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.dealsourcing.persistence.ProspectStore
import com.octo.dealsourcing.persistence.ScreeningRuleRow
import com.octo.dealsourcing.registered
import com.octo.workflow.Task
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.TaskStatus
import com.octo.workflow.persistence.TaskProvenance
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
import java.time.Instant
import java.util.UUID

/** The workflow tasks the IC gate opens and reads; `JdbcTaskStore` behind it in production. */
interface IcTasks {
    fun open(
        task: Task,
        provenance: TaskProvenance,
    )

    fun state(taskId: UUID): TaskState?
}

/** The tenant's versioned screening rules; `JdbcScreeningRuleStore` behind it in production. */
interface ScreeningRules {
    /** Appends the next version of [ruleId]; returns the version written. */
    fun define(
        tenantId: UUID,
        ruleId: String,
        name: String,
        criteria: String,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ): Int

    fun activeRules(
        tenantId: UUID,
        scope: TenantScope,
    ): List<ScreeningRuleRow>
}

/** One import call registers at most this many prospects — adapters page larger syncs themselves. */
private const val IMPORT_BATCH_LIMIT = 500

/**
 * `/api/v1/prospects` (deal-sourcing, #201): the deterministic pipeline the Investment Analyst and CRM
 * workflows will screen on. Registration and transitions are tenant writes — a `viewer` may read every
 * prospect of its tenant but, like every other write here, sees 404 rather than 403 so nothing leaks
 * (default deny, data-security-governance.md). The stage machine validates in the store: a rejected
 * transition answers 409, an unknown prospect 404.
 */
@RestController
class ProspectController(
    private val prospects: ProspectStore,
    private val tasks: IcTasks,
    private val rules: ScreeningRules,
    private val tenants: TenantDirectory,
    private val json: com.fasterxml.jackson.databind.ObjectMapper,
) {
    @PostMapping("/api/v1/prospects")
    fun register(
        @RequestBody body: RegisterRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ProspectView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val prospect =
            runCatching {
                Prospect(
                    id = UUID.randomUUID(),
                    tenantId = body.tenantId,
                    name = body.name,
                    source = ProspectSource.fromWireValue(body.source),
                    sector = body.sector,
                    region = body.region,
                    description = body.description,
                    registeredAt = Instant.now(),
                )
            }.getOrNull() ?: return ResponseEntity.badRequest().build()
        prospects.create(
            prospect,
            jwt.subject,
            ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
            TenantScope.User(userId),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(registered(prospect).view())
    }

    @GetMapping("/api/v1/prospects/{id}")
    fun prospect(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ProspectView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val state = prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        roleIn(userId, state.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(state.view())
    }

    /**
     * `POST /api/v1/prospects/import` — the CRM/referral adapter's bulk intake. Each item registers
     * deduplicated on `(tenant, source, source_ref)`: a re-sync is a no-op, so adapters can poll
     * freely without duplicate prospects. Items without `sourceRef` always register. Batches are
     * capped at [IMPORT_BATCH_LIMIT] so one request can't hold an unbounded transaction open.
     */
    @PostMapping("/api/v1/prospects/import")
    fun import(
        @RequestBody body: ImportRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ImportView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (body.items.isEmpty() || body.items.size > IMPORT_BATCH_LIMIT) {
            return ResponseEntity.badRequest().build()
        }
        val registered = Instant.now()
        val items =
            body.items.mapNotNull { item ->
                runCatching {
                    Prospect(
                        id = UUID.randomUUID(),
                        tenantId = body.tenantId,
                        name = item.name,
                        source = ProspectSource.fromWireValue(item.source),
                        sector = item.sector,
                        region = item.region,
                        description = item.description,
                        registeredAt = registered,
                        sourceRef = item.sourceRef,
                    )
                }.getOrNull() ?: return ResponseEntity.badRequest().build()
            }
        val inserted =
            prospects.importBatch(
                items,
                jwt.subject,
                ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
                TenantScope.User(userId),
            )
        return ResponseEntity.ok(ImportView(inserted.size, items.size - inserted.size, inserted))
    }

    /**
     * `GET /api/v1/prospects/{id}/events` — the append-only audit trail itself: every transition with
     * actor, rationale, business/recorded time, provenance correlation, and the IC task that
     * authorized an `invested`. Read-side mirror of why the store keeps events, not just state.
     */
    @GetMapping("/api/v1/prospects/{id}/events")
    fun events(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<EventView>> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val state = prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        roleIn(userId, state.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        val rows = prospects.history(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(rows.map { it.view() })
    }

    @GetMapping("/api/v1/prospects")
    fun pipeline(
        @RequestParam tenantId: UUID,
        @RequestParam stage: String,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<ProspectView>> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        roleIn(userId, tenantId) ?: return ResponseEntity.notFound().build()
        val stageAt =
            runCatching { ProspectStage.fromWireValue(stage) }.getOrNull()
                ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(
            prospects.listAtStage(tenantId, stageAt, TenantScope.User(userId)).map { it.view() },
        )
    }

    @PostMapping("/api/v1/prospects/{id}/transition")
    fun transition(
        @PathVariable id: UUID,
        @RequestBody body: TransitionRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ProspectView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val current =
            prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, current.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val to =
            runCatching { ProspectStage.fromWireValue(body.to) }.getOrNull()
                ?: return ResponseEntity.badRequest().build()
        val at = Instant.now()
        val event =
            when (to) {
                ProspectStage.PASSED ->
                    body.rationale
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ProspectEvent.Passed(jwt.subject, at, current.stage, it) }
                        ?: return ResponseEntity.badRequest().build()
                ProspectStage.INVESTED -> {
                    val rationale =
                        body.rationale?.takeIf { it.isNotBlank() }
                            ?: return ResponseEntity.badRequest().build()
                    val taskId = body.taskId ?: return ResponseEntity.badRequest().build()
                    if (!icApproved(id, taskId)) return ResponseEntity.status(HttpStatus.CONFLICT).build()
                    ProspectEvent.Invested(jwt.subject, at, rationale, taskId)
                }
                else -> ProspectEvent.Advanced(jwt.subject, at, current.stage, to)
            }
        val after =
            try {
                prospects.append(
                    id,
                    event,
                    ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
                    TenantScope.User(userId),
                )
            } catch (_: NoSuchElementException) {
                return ResponseEntity.notFound().build()
            } catch (_: IllegalArgumentException) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        if (event is ProspectEvent.Advanced && after.stage == ProspectStage.DUE_DILIGENCE) {
            openDiligenceChecklist(id, jwt.subject)
        }
        return ResponseEntity.ok(after.view())
    }

    /**
     * Landing in `due-diligence` opens the evidence checklist: a REVIEW-kind task on the prospect.
     * The task state machine tracks who gathers and closes it; the pipeline itself only demands
     * that the request exists — diligence output (DDQ, docs) is a later slice.
     */
    private fun openDiligenceChecklist(
        prospectId: UUID,
        requester: String,
    ) {
        tasks.open(
            Task(
                UUID.randomUUID(),
                TaskKind.EVIDENCE_REQUEST,
                "prospect",
                prospectId.toString(),
                requester,
                Instant.now(),
            ),
            TaskProvenance("api", UUID.randomUUID()),
        )
    }

    /**
     * The IC gate (V19): `invested` is authorized only by a `workflow_task` of kind approval that
     * already reached APPROVED on this prospect — the task's own state machine enforced approver ≠
     * requester (Task.kt), so one person can never both submit and approve an investment.
     */
    private fun icApproved(
        prospectId: UUID,
        taskId: UUID,
    ): Boolean =
        tasks.state(taskId)?.let {
            it.status == TaskStatus.APPROVED &&
                it.task.kind == TaskKind.APPROVAL &&
                it.task.subjectType == "prospect" &&
                it.task.subjectId == prospectId.toString()
        } == true

    @PostMapping("/api/v1/prospects/{id}/ic-review")
    fun requestIcReview(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<IcView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val current =
            prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, current.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (current.stage != ProspectStage.IC_REVIEW) return ResponseEntity.status(HttpStatus.CONFLICT).build()
        val task =
            Task(
                UUID.randomUUID(),
                TaskKind.APPROVAL,
                "prospect",
                id.toString(),
                jwt.subject,
                Instant.now(),
            )
        tasks.open(task, TaskProvenance("api", UUID.randomUUID()))
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(IcView(task.id, TaskStatus.OPEN.name.lowercase()))
    }

    /**
     * `POST /api/v1/screening-rules` — appends the next version of a tenant's screening rule (V20).
     * Criteria are validated against the domain model at the boundary: an unknown `sources` value
     * fails closed with 400 rather than persisting a rule that can never match.
     */
    @PostMapping("/api/v1/screening-rules")
    fun defineRule(
        @RequestBody body: RuleRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RuleView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (body.ruleId.isBlank() || body.name.isBlank()) return ResponseEntity.badRequest().build()
        runCatching { ScreeningCriteria.parse(body.criteria) }
            .getOrElse { return ResponseEntity.badRequest().build() }
        val version =
            rules.define(
                body.tenantId,
                body.ruleId,
                body.name,
                json.writeValueAsString(body.criteria),
                jwt.subject,
                ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
                TenantScope.User(userId),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(RuleView(body.ruleId, version))
    }

    /**
     * `POST /api/v1/prospects/{id}/screen` — evaluates the tenant's active rules conjunctively:
     * REJECT appends `passed` with the violated constraints as its rationale, REVIEW opens a review
     * task (a person resolves what data could not), CLEAR leaves the prospect eligible to advance.
     */
    @PostMapping("/api/v1/prospects/{id}/screen")
    fun screen(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ScreenView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val current =
            prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, current.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (current.stage != ProspectStage.SCREENING) return ResponseEntity.status(HttpStatus.CONFLICT).build()
        val scope = TenantScope.User(userId)
        val outcome = screen(current.prospect, scope)
        when (outcome.verdict) {
            ScreeningVerdict.REJECT ->
                prospects.append(
                    id,
                    ProspectEvent.Passed(
                        jwt.subject,
                        Instant.now(),
                        current.stage,
                        "screened out: ${outcome.reasons.joinToString("; ")}",
                    ),
                    ProspectProvenance("api", UUID.randomUUID()),
                    TenantScope.User(userId),
                )
            ScreeningVerdict.REVIEW -> {
                val task =
                    Task(
                        UUID.randomUUID(),
                        TaskKind.REVIEW,
                        "prospect",
                        id.toString(),
                        jwt.subject,
                        Instant.now(),
                    )
                tasks.open(task, TaskProvenance("api", UUID.randomUUID()))
                return ResponseEntity.ok(
                    ScreenView(outcome.verdict.name.lowercase(), outcome.reasons, current.stage.wireValue, task.id),
                )
            }
            else -> {}
        }
        val stage = prospects.load(id, TenantScope.User(userId))?.stage?.wireValue ?: current.stage.wireValue
        return ResponseEntity.ok(ScreenView(outcome.verdict.name.lowercase(), outcome.reasons, stage, null))
    }

    private fun screen(
        prospect: Prospect,
        scope: TenantScope,
    ): ScreeningOutcome {
        val rows = rules.activeRules(prospect.tenantId, scope)
        if (rows.isEmpty()) {
            return ScreeningOutcome(
                ScreeningVerdict.REVIEW,
                listOf("no active screening rule for the tenant"),
            )
        }
        val parsed =
            rows.map { row ->
                val fields: Map<String, List<String>> =
                    json.readValue(
                        row.criteria,
                        object : com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {},
                    )
                row.name to ScreeningCriteria.parse(fields)
            }
        return evaluateAll(prospect, parsed)
    }

    private fun roleIn(
        userId: UUID,
        tenantId: UUID,
    ): TenantRole? = tenants.tenantsOf(userId).firstOrNull { it.tenantId == tenantId }?.role

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject) }.getOrNull()

    private fun com.octo.dealsourcing.persistence.ProspectEventRow.view() =
        EventView(
            seq = seq,
            eventType = eventType,
            stageFrom = stageFrom?.wireValue,
            stageTo = stageTo?.wireValue,
            actor = actor,
            rationale = rationale,
            occurredAt = occurredAt,
            recordedAt = recordedAt,
            correlationId = correlationId,
            taskId = taskId,
        )

    private fun ProspectState.view() =
        ProspectView(
            id = prospect.id,
            tenantId = prospect.tenantId,
            name = prospect.name,
            source = prospect.source.wireValue,
            sector = prospect.sector,
            region = prospect.region,
            description = prospect.description,
            registeredAt = prospect.registeredAt,
            stage = stage.wireValue,
            decidedBy = decidedBy,
            lastEventAt = lastEventAt,
        )

    data class ImportItem(
        val name: String,
        val source: String,
        val sourceRef: String? = null,
        val sector: String? = null,
        val region: String? = null,
        val description: String? = null,
    )

    data class ImportRequest(
        val tenantId: UUID,
        val items: List<ImportItem>,
        val correlationId: UUID? = null,
    )

    data class ImportView(
        val inserted: Int,
        val duplicates: Int,
        val ids: List<UUID>,
    )

    data class RegisterRequest(
        val tenantId: UUID,
        val name: String,
        val source: String,
        val sector: String? = null,
        val region: String? = null,
        val description: String? = null,
        val correlationId: UUID? = null,
    )

    data class TransitionRequest(
        val to: String,
        val rationale: String? = null,
        val taskId: UUID? = null,
        val correlationId: UUID? = null,
    )

    data class IcView(
        val taskId: UUID,
        val taskStatus: String,
    )

    data class RuleRequest(
        val tenantId: UUID,
        val ruleId: String,
        val name: String,
        val criteria: Map<String, List<String>>,
        val correlationId: UUID? = null,
    )

    data class RuleView(
        val ruleId: String,
        val version: Int,
    )

    data class ScreenView(
        val verdict: String,
        val reasons: List<String>,
        val stage: String,
        val reviewTaskId: UUID? = null,
    )

    data class EventView(
        val seq: Long,
        val eventType: String,
        val stageFrom: String?,
        val stageTo: String?,
        val actor: String,
        val rationale: String?,
        val occurredAt: Instant,
        val recordedAt: Instant,
        val correlationId: UUID,
        val taskId: UUID?,
    )

    data class ProspectView(
        val id: UUID,
        val tenantId: UUID,
        val name: String,
        val source: String,
        val sector: String?,
        val region: String?,
        val description: String?,
        val registeredAt: Instant,
        val stage: String,
        val decidedBy: String?,
        val lastEventAt: Instant,
    )
}
