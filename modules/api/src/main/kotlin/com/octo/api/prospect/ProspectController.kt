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
import com.octo.dealsourcing.evaluateAll
import com.octo.dealsourcing.next
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.dealsourcing.persistence.ProspectStore
import com.octo.dealsourcing.persistence.ScreeningRuleRow
import com.octo.dealsourcing.registered
import com.octo.persistence.TenantScope
import com.octo.workflow.Task
import com.octo.workflow.TaskEvent
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

/** The workflow tasks the IC gate opens, reads, and decides; `JdbcTaskStore` behind it in production. */
interface IcTasks {
    fun open(
        task: Task,
        provenance: TaskProvenance,
    )

    fun state(taskId: UUID): TaskState?

    /** Applies [event] to the task through its state machine — the same validation `append` gets. */
    fun append(
        taskId: UUID,
        event: TaskEvent,
        provenance: TaskProvenance,
    ): TaskState

    /** Every task opened on one subject, in creation order — the dedupe lookup for task opens. */
    fun listForSubject(
        subjectType: String,
        subjectId: String,
    ): List<TaskState>
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

/** One pipeline read returns at most this many prospects — every read is bounded. */
private const val PIPELINE_PAGE_LIMIT = 500

/** The shape V20 enforces on `screening_rule.rule_id`; the edge validates it before the store sees it. */
private val RULE_ID_SHAPE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

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

    /**
     * `GET /api/v1/prospects?tenantId&stage&limit&offset` — the pipeline read, newest registrations
     * first. Reads are paged (`limit` ≤ [PIPELINE_PAGE_LIMIT], default 200) so a large stage can't
     * answer with an unbounded list.
     */
    @GetMapping("/api/v1/prospects")
    fun pipeline(
        @RequestParam tenantId: UUID,
        @RequestParam stage: String,
        @RequestParam(defaultValue = "200") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<List<ProspectView>> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        roleIn(userId, tenantId) ?: return ResponseEntity.notFound().build()
        val stageAt =
            runCatching { ProspectStage.fromWireValue(stage) }.getOrNull()
                ?: return ResponseEntity.badRequest().build()
        if (limit !in 1..PIPELINE_PAGE_LIMIT || offset < 0) return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(
            prospects.listAtStage(tenantId, stageAt, limit, offset, TenantScope.User(userId)).map { it.view() },
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
        // Validate through the same state machine the store replays under its lock, before any
        // write — a transition that cannot land answers 409 without opening its checklist.
        val landing =
            runCatching { current.next(event) }.getOrNull()
                ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()
        // The checklist opens before the event commits: if this open fails nothing is written
        // anywhere, and a retried transition still finds the task it needs (deduped below).
        if (landing.stage == ProspectStage.DUE_DILIGENCE) {
            openDiligenceChecklist(id, jwt.subject)
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
        return ResponseEntity.ok(after.view())
    }

    /**
     * Landing in `due-diligence` needs an evidence checklist: an EVIDENCE_REQUEST task on the
     * prospect. Idempotent — a transition retried after the task opened but before the event
     * committed reuses the checklist it left; the task state machine tracks who gathers and
     * closes it; diligence output (DDQ, docs) is a later slice.
     */
    private fun openDiligenceChecklist(
        prospectId: UUID,
        requester: String,
    ) {
        val existing =
            tasks
                .listForSubject("prospect", prospectId.toString())
                .any { it.task.kind == TaskKind.EVIDENCE_REQUEST && !it.status.terminal }
        if (existing) return
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
        // Idempotent: an approval task already in flight is the review — repeated calls return it
        // rather than minting a second approval on the same prospect. A decided task is history;
        // asking again opens a fresh review.
        val existing =
            tasks
                .listForSubject("prospect", id.toString())
                .firstOrNull { it.task.kind == TaskKind.APPROVAL && !it.status.terminal }
        if (existing != null) {
            return ResponseEntity.ok(IcView(existing.task.id, existing.status.name.lowercase()))
        }
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
     * `POST /api/v1/prospects/{id}/tasks/{taskId}` — applies one task event to a task whose subject
     * is this prospect: an IC member approves or returns the approval `ic-review` opened, a person
     * completes the evidence checklist due-diligence raised, the requester resubmits after rework.
     * The task state machine holds every rule — nobody decides an approval they requested, only the
     * requester resubmits, terminal tasks accept nothing — so the edge binds the task to this
     * prospect and the caller's role, and a rejected event answers 409 like a bad stage transition.
     * A task on any other subject is 404: the route never reveals it exists.
     */
    @PostMapping("/api/v1/prospects/{id}/tasks/{taskId}")
    fun taskEvent(
        @PathVariable id: UUID,
        @PathVariable taskId: UUID,
        @RequestBody body: TaskEventRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<TaskView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val current =
            prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, current.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        tasks
            .state(taskId)
            ?.takeIf { it.task.subjectType == "prospect" && it.task.subjectId == id.toString() }
            ?: return ResponseEntity.notFound().build()
        val event = body.toEvent(jwt.subject) ?: return ResponseEntity.badRequest().build()
        val after =
            try {
                tasks.append(taskId, event, TaskProvenance("api", body.correlationId ?: UUID.randomUUID()))
            } catch (_: NoSuchElementException) {
                return ResponseEntity.notFound().build()
            } catch (_: IllegalArgumentException) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        return ResponseEntity.ok(after.view())
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
        if (!RULE_ID_SHAPE.matches(body.ruleId) || body.name.isBlank()) return ResponseEntity.badRequest().build()
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
                try {
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
                } catch (_: NoSuchElementException) {
                    return ResponseEntity.notFound().build()
                } catch (_: IllegalArgumentException) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).build()
                }
            ScreeningVerdict.REVIEW -> {
                // Idempotent like ic-review: screening an already-flagged prospect returns the
                // review task a person is working, not another copy of the same ask.
                val taskId =
                    tasks
                        .listForSubject("prospect", id.toString())
                        .firstOrNull { it.task.kind == TaskKind.REVIEW && !it.status.terminal }
                        ?.task
                        ?.id
                        ?: run {
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
                            task.id
                        }
                return ResponseEntity.ok(
                    ScreenView(outcome.verdict.name.lowercase(), outcome.reasons, current.stage.wireValue, taskId),
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

    private fun TaskState.view() =
        TaskView(
            taskId = task.id,
            kind = task.kind.wireValue,
            status = status.name.lowercase(),
            assignee = assignee,
            decidedBy = decidedBy,
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

    /**
     * One event for the task endpoint, named by its `workflow_task_event.event_type` wire value.
     * `assignee` is required by `assigned`; `rationale` by `rejected`, `rework-requested` and
     * `cancelled`; anything else missing answers 400 before the state machine sees it.
     */
    data class TaskEventRequest(
        val event: String,
        val rationale: String? = null,
        val assignee: String? = null,
        val correlationId: UUID? = null,
    ) {
        fun toEvent(actor: String): TaskEvent? {
            val at = Instant.now()
            return when (event) {
                "assigned" -> assignee?.takeIf { it.isNotBlank() }?.let { TaskEvent.Assigned(actor, at, it) }
                "approved" -> TaskEvent.Approved(actor, at, rationale)
                "rejected" -> rationale?.takeIf { it.isNotBlank() }?.let { TaskEvent.Rejected(actor, at, it) }
                "rework-requested" -> rationale?.takeIf { it.isNotBlank() }?.let { TaskEvent.ReworkRequested(actor, at, it) }
                "resubmitted" -> TaskEvent.Resubmitted(actor, at)
                "completed" -> TaskEvent.Completed(actor, at, rationale)
                "cancelled" -> rationale?.takeIf { it.isNotBlank() }?.let { TaskEvent.Cancelled(actor, at, it) }
                else -> null
            }
        }
    }

    data class TaskView(
        val taskId: UUID,
        val kind: String,
        val status: String,
        val assignee: String?,
        val decidedBy: String?,
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
