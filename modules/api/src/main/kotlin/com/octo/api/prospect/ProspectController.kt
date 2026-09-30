package com.octo.api.prospect

import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.agents.AgentsCallException
import com.octo.api.agents.AgentsClient
import com.octo.api.agents.AgentsUnavailableException
import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.ScreeningCriteria
import com.octo.dealsourcing.ScreeningOutcome
import com.octo.dealsourcing.ScreeningVerdict
import com.octo.dealsourcing.combine
import com.octo.dealsourcing.evaluate
import com.octo.dealsourcing.next
import com.octo.dealsourcing.persistence.IMPORT_BATCH_LIMIT
import com.octo.dealsourcing.persistence.PIPELINE_PAGE_LIMIT
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
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
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

/**
 * The workflow tasks the IC gate opens, reads, and decides; `JdbcTaskStore` behind it in production.
 * Tasks carry no tenant column (V5 predates tenant scoping) so this interface is deliberately
 * unscoped — isolation comes from the edge, which only ever asks for tasks bound to a prospect whose
 * tenant the caller already proved a role in. Do not reuse it for a lookup that skips that check.
 */
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

    /**
     * The atomic open [listForSubject]+[open] cannot guarantee: returns the task of [task]'s kind
     * already open on the subject, or opens [task] — serialized per subject, so racing callers can
     * never mint a duplicate. A returned id equal to `task.id` means this call did the opening.
     */
    fun openUnlessOpen(
        task: Task,
        provenance: TaskProvenance,
    ): TaskState
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

    /**
     * Retires [ruleId] — a new, inactive version, so the rule leaves the active set without losing
     * its history. Returns the version written (or the current version if already retired), or null
     * when the tenant has no such rule.
     */
    fun retire(
        tenantId: UUID,
        ruleId: String,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ): Int?

    fun activeRules(
        tenantId: UUID,
        scope: TenantScope,
    ): List<ScreeningRuleRow>
}

/** Field-length bounds the edge enforces before any text reaches a `text` column. */
private const val NAME_LIMIT = 300
private const val FIELD_LIMIT = 200
private const val DESCRIPTION_LIMIT = 10_000

/** A screening rule constrains at most this many allowed values per field. */
private const val CRITERIA_LIST_LIMIT = 100

/** Stages at or past `due-diligence` — the only stages that can have a committed landing claiming a checklist. */
private val CHECKLIST_CLAIM_STAGES = setOf(ProspectStage.DUE_DILIGENCE, ProspectStage.IC_REVIEW, ProspectStage.INVESTED)

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
    private val agents: AgentsClient,
    private val json: com.fasterxml.jackson.databind.ObjectMapper,
    meters: ObjectProvider<MeterRegistry>,
) {
    private val meters = meters.getIfAvailable()

    /** `deal.prospects.*` — the pipeline's business counters, tagged like the EVM runner's. */
    private fun counter(
        name: String,
        vararg tags: String,
    ): Counter? = meters?.let { Counter.builder(name).tags(*tags).register(it) }

    @PostMapping("/api/v1/prospects")
    fun register(
        @RequestBody body: RegisterRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ProspectView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        if (!fieldsBounded(body.name, body.sector, body.region, null, body.description)) {
            return ResponseEntity.badRequest().build()
        }
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
            jwt.subject!!,
            ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
            TenantScope.User(userId),
        )
        counter("deal.prospects.registered")?.increment()
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
        if (body.items.any { !fieldsBounded(it.name, it.sector, it.region, it.sourceRef, it.description) }) {
            return ResponseEntity.badRequest().build()
        }
        val registered = Instant.now()
        val items =
            body.items.map { item ->
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
                jwt.subject!!,
                ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
                TenantScope.User(userId),
            )
        counter("deal.prospects.imported", "result", "inserted")?.increment(inserted.size.toDouble())
        counter("deal.prospects.imported", "result", "duplicate")?.increment((items.size - inserted.size).toDouble())
        return ResponseEntity.ok(ImportView(inserted.size, items.size - inserted.size, inserted))
    }

    /**
     * `GET /api/v1/prospects/{id}/events` — the append-only audit trail itself: every transition with
     * actor, rationale, business/recorded time, provenance correlation, and the task lineage it
     * claims — the checklist a `due-diligence` landing runs on, the approval that authorized an
     * `invested`. Read-side mirror of why the store keeps events, not just state.
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
                        ?.let { ProspectEvent.Passed(jwt.subject!!, at, current.stage, it) }
                        ?: return ResponseEntity.badRequest().build()
                ProspectStage.INVESTED -> {
                    val rationale =
                        body.rationale?.takeIf { it.isNotBlank() }
                            ?: return ResponseEntity.badRequest().build()
                    val taskId = body.taskId ?: return ResponseEntity.badRequest().build()
                    if (!icApproved(id, taskId)) return ResponseEntity.status(HttpStatus.CONFLICT).build()
                    ProspectEvent.Invested(jwt.subject!!, at, rationale, taskId)
                }
                else -> ProspectEvent.Advanced(jwt.subject!!, at, current.stage, to)
            }
        // Validate through the same state machine the store replays under its lock, before any
        // write — a transition that cannot land answers 409 without opening its checklist.
        val landing =
            runCatching { current.next(event) }.getOrNull()
                ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()
        // The checklist opens before the event commits: if this open fails nothing is written
        // anywhere, and a retried transition still finds the task it needs (deduped below). The
        // landing records which task it claims — like `invested` records its approval — so a lost
        // race reads back whether the committed landing named this request's task before deciding
        // to cancel it: a claimed task serves the winner, an unclaimed one is an orphan no one can
        // ever close.
        val checklist =
            if (landing.stage == ProspectStage.DUE_DILIGENCE) openDiligenceChecklist(id, jwt.subject!!) else null
        val appendEvent =
            if (event is ProspectEvent.Advanced && checklist != null) event.copy(taskId = checklist.taskId) else event
        val after =
            try {
                prospects.append(
                    id,
                    appendEvent,
                    ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
                    TenantScope.User(userId),
                )
            } catch (_: NoSuchElementException) {
                checklist?.mintedId?.let { cancelOrphanedChecklist(it, jwt.subject!!) }
                return ResponseEntity.notFound().build()
            } catch (_: IllegalArgumentException) {
                cancelUnlessClaimed(checklist, id, userId, jwt.subject!!)
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            } catch (failure: Exception) {
                // Any other store failure commits nothing either — the minted checklist gets the same cleanup.
                cancelUnlessClaimed(checklist, id, userId, jwt.subject!!)
                throw failure
            }
        counter("deal.prospects.transitions", "to", to.wireValue)?.increment()
        return ResponseEntity.ok(after.view())
    }

    /** The task the landing runs on ([taskId], recorded on its event) and, only when this call minted it, [mintedId] — the sole task the call may cancel if the landing never commits. */
    private data class Checklist(
        val taskId: UUID,
        val mintedId: UUID?,
    )

    /**
     * Landing in `due-diligence` needs an evidence checklist: an EVIDENCE_REQUEST task on the
     * prospect. Idempotent — a transition retried after the task opened but before the event
     * committed reuses the checklist it left; the task state machine tracks who gathers and
     * closes it; diligence output (DDQ, docs) is a later slice. The landing records [Checklist.taskId]
     * on its event — the same lineage `invested` keeps for its approval — so a lost race reads the
     * claim back. Only a task this call minted may ever be cancelled on its behalf; a reused one
     * belongs to an earlier attempt and outlives this failure.
     */
    private fun openDiligenceChecklist(
        prospectId: UUID,
        requester: String,
    ): Checklist {
        val candidate =
            Task(
                UUID.randomUUID(),
                TaskKind.EVIDENCE_REQUEST,
                "prospect",
                prospectId.toString(),
                requester,
                Instant.now(),
            )
        // One store call serializes the check and the insert — a racing transition can never mint
        // a second checklist. The id is only safe to hand back for cancellation when this call
        // inserted: a reused task belongs to an earlier attempt and outlives this failure.
        val opened = tasks.openUnlessOpen(candidate, TaskProvenance("api", UUID.randomUUID()))
        return Checklist(opened.task.id, opened.task.id.takeIf { it == candidate.id })
    }

    /**
     * Whether the task this request minted is the checklist the pipeline runs on. The prospect must
     * stand at `due-diligence` or beyond — earlier stages and a terminal pass claim nothing — and
     * the committed `due-diligence` landing must name this task. A landing row written before claim
     * lineage existed (no task id), or a post-DD stage whose landing row is missing, keeps the task:
     * a doubtful claim must never cancel a checklist that may be in service.
     */
    private fun checklistClaimed(
        prospectId: UUID,
        taskId: UUID,
        userId: UUID,
    ): Boolean {
        val rows = prospects.history(prospectId, TenantScope.User(userId)) ?: return false
        val stage = rows.lastOrNull()?.stageTo ?: ProspectStage.SOURCED
        if (stage !in CHECKLIST_CLAIM_STAGES) return false
        val landing = rows.firstOrNull { it.stageTo == ProspectStage.DUE_DILIGENCE } ?: return true
        return landing.taskId == null || landing.taskId == taskId
    }

    /** Cancels a checklist this request minted unless a committed landing claims it — the cleanup every failed append path shares. A claim read that itself fails keeps the task: cancelling a checklist that may be in service is the one outcome this path must never cause. */
    private fun cancelUnlessClaimed(
        checklist: Checklist?,
        prospectId: UUID,
        userId: UUID,
        actor: String,
    ) {
        val mintedId = checklist?.mintedId ?: return
        val claimed = runCatching { checklistClaimed(prospectId, mintedId, userId) }.getOrDefault(true)
        if (!claimed) cancelOrphanedChecklist(mintedId, actor)
    }

    /** The transition did not land, so the checklist opened for it has no state to serve — cancelled, never left open. */
    private fun cancelOrphanedChecklist(
        taskId: UUID,
        actor: String,
    ) {
        runCatching {
            tasks.append(
                taskId,
                TaskEvent.Cancelled(actor, Instant.now(), "the due-diligence transition did not land"),
                TaskProvenance("api", UUID.randomUUID()),
            )
        }
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
        // Idempotent and atomic: an approval task already in flight is the review — repeated or
        // racing calls return it rather than minting a second approval on the same prospect. A
        // decided task is history; asking again opens a fresh review.
        val candidate =
            Task(
                UUID.randomUUID(),
                TaskKind.APPROVAL,
                "prospect",
                id.toString(),
                jwt.subject!!,
                Instant.now(),
            )
        val review = tasks.openUnlessOpen(candidate, TaskProvenance("api", UUID.randomUUID()))
        if (review.task.id != candidate.id) {
            counter("deal.prospects.ic_reviews", "result", "in-flight")?.increment()
            return ResponseEntity.ok(IcView(review.task.id, review.status.name.lowercase()))
        }
        counter("deal.prospects.ic_reviews", "result", "opened")?.increment()
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(IcView(review.task.id, review.status.name.lowercase()))
    }

    /**
     * `POST /api/v1/prospects/{id}/tasks/{taskId}` — applies one task event to a task whose subject
     * is this prospect: an IC member approves or returns the approval `ic-review` opened, a person
     * completes the evidence checklist due-diligence raised, the requester resubmits after rework.
     * The task state machine holds every rule — nobody decides an approval they requested, only the
     * requester resubmits, terminal tasks accept nothing — and the edge adds the governance bar the
     * role model states: gate decisions on an `approval` task (approve, reject, rework, cancel)
     * need `approver` or `admin`, like compliance-rule writes (ComplianceController). A task on any
     * other subject is 404: the route never reveals it exists.
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
        val bound =
            tasks
                .state(taskId)
                ?.takeIf {
                    it.task.subjectType == "prospect" &&
                        // DD workstream tasks carry "{id}:dd:{workstream}" — the same prospect scope,
                        // a distinct subject so each stream holds one open evidence request.
                        (it.task.subjectId == id.toString() || it.task.subjectId.startsWith("$id:dd:"))
                } ?: return ResponseEntity.notFound().build()
        val event = body.toEvent(jwt.subject!!) ?: return ResponseEntity.badRequest().build()
        // A gate decision on an approval task is governance, not working access: the same
        // approver-or-admin bar the compliance-rule endpoints apply (ComplianceController). Routing
        // the task (assigned) or the requester resubmitting after rework stays a working action.
        if (bound.task.kind == TaskKind.APPROVAL && event.isGateDecision() && role != TenantRole.APPROVER && role != TenantRole.ADMIN) {
            return ResponseEntity.notFound().build()
        }
        val after =
            try {
                tasks.append(taskId, event, TaskProvenance("api", body.correlationId ?: UUID.randomUUID()))
            } catch (_: NoSuchElementException) {
                return ResponseEntity.notFound().build()
            } catch (_: IllegalArgumentException) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        counter("deal.prospects.task_events", "event", body.event)?.increment()
        return ResponseEntity.ok(after.view())
    }

    /**
     * `POST /api/v1/prospects/{id}/dd-evidence` — the mediated write the DD workflow ends in
     * (ADR-0005 F3): a jev-banded risky workstream becomes an EVIDENCE_REQUEST task on subject
     * "{id}:dd:{workstream}" — one open task per stream, `openUnlessOpen` makes a retried run
     * reuse rather than duplicate. Member-or-better like every write here; the model that flagged
     * the gap never touches the store. The gap text lives in the agent_run record — the task is
     * the checklist entry a human gathers against and completes through /tasks/{taskId}.
     */
    @PostMapping("/api/v1/prospects/{id}/dd-evidence")
    fun openDdEvidence(
        @PathVariable id: UUID,
        @RequestBody body: DdEvidenceRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val current =
            prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, current.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val workstream = body.workstream.trim()
        if (!workstream.matches(Regex("[a-z][a-z0-9-]{0,62}")) || body.summary.isBlank() ||
            body.summary.length > DESCRIPTION_LIMIT
        ) {
            return ResponseEntity.badRequest().build()
        }
        val candidate =
            Task(
                UUID.randomUUID(),
                TaskKind.EVIDENCE_REQUEST,
                "prospect",
                "$id:dd:$workstream",
                jwt.subject!!,
                Instant.now(),
            )
        val opened =
            tasks.openUnlessOpen(candidate, TaskProvenance("api", body.correlationId ?: UUID.randomUUID()))
        counter("deal.prospects.dd_evidence_tasks", "workstream", workstream)?.increment()
        return ResponseEntity.ok(
            mapOf(
                "taskId" to opened.task.id,
                "workstream" to workstream,
                "opened" to (opened.task.id == candidate.id),
            ),
        )
    }

    /**
     * `POST /api/v1/screening-rules` — appends the next version of a tenant's screening rule (V20).
     * Writing rules is governance, so like `POST /compliance/rules` it needs `approver` or `admin`.
     * Criteria are validated against the domain model at the boundary: an unknown field name or a
     * bad `sources` value fails closed with 400 rather than persisting a rule that can never match.
     */
    @PostMapping("/api/v1/screening-rules")
    fun defineRule(
        @RequestBody body: RuleRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RuleView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        // Rules are governance — the same approver-or-admin bar `POST /compliance/rules` applies.
        if (role != TenantRole.APPROVER && role != TenantRole.ADMIN) return ResponseEntity.notFound().build()
        if (!RULE_ID_SHAPE.matches(body.ruleId) || body.name.isBlank() || body.name.length > NAME_LIMIT) {
            return ResponseEntity.badRequest().build()
        }
        // Unknown keys are refused, not ignored — a typo'd field would otherwise persist a rule
        // that silently clears everything it screens.
        if (body.criteria.keys.any { it !in ScreeningCriteria.FIELD_NAMES }) return ResponseEntity.badRequest().build()
        // An empty allowed set is not "unconstrained" — it can never match, a rule that rejects or
        // reviews every prospect it touches; treat it as the misconfiguration it is.
        if (
            body.criteria.values.any { values ->
                values.isEmpty() || values.size > CRITERIA_LIST_LIMIT || values.any { it.length > FIELD_LIMIT }
            }
        ) {
            return ResponseEntity.badRequest().build()
        }
        runCatching { ScreeningCriteria.parse(body.criteria) }
            .getOrElse { return ResponseEntity.badRequest().build() }
        val version =
            rules.define(
                body.tenantId,
                body.ruleId,
                body.name,
                json.writeValueAsString(body.criteria),
                jwt.subject!!,
                ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
                TenantScope.User(userId),
            )
        counter("deal.prospects.rules_defined")?.increment()
        return ResponseEntity.status(HttpStatus.CREATED).body(RuleView(body.ruleId, version))
    }

    /**
     * `POST /api/v1/screening-rules/{ruleId}/retire` — takes a rule out of the active set the
     * append-only way: a new version marked inactive (V20). The criteria that governed past
     * verdicts stay auditable, a later define re-activates the rule, and an unknown rule answers
     * 404 like every other miss here.
     */
    @PostMapping("/api/v1/screening-rules/{ruleId}/retire")
    fun retireRule(
        @PathVariable ruleId: String,
        @RequestBody body: RetireRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RuleView> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, body.tenantId) ?: return ResponseEntity.notFound().build()
        if (role != TenantRole.APPROVER && role != TenantRole.ADMIN) return ResponseEntity.notFound().build()
        val version =
            rules.retire(
                body.tenantId,
                ruleId,
                jwt.subject!!,
                ProspectProvenance("api", body.correlationId ?: UUID.randomUUID()),
                TenantScope.User(userId),
            ) ?: return ResponseEntity.notFound().build()
        counter("deal.prospects.rules_retired")?.increment()
        return ResponseEntity.ok(RuleView(ruleId, version))
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
                            jwt.subject!!,
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
                // Idempotent like ic-review, and atomic: screening an already-flagged prospect
                // returns the review task a person is working, never a second copy of the same ask.
                val taskId =
                    tasks
                        .openUnlessOpen(
                            Task(
                                UUID.randomUUID(),
                                TaskKind.REVIEW,
                                "prospect",
                                id.toString(),
                                jwt.subject!!,
                                Instant.now(),
                            ),
                            TaskProvenance("api", UUID.randomUUID()),
                        ).task
                        .id
                counter("deal.prospects.screens", "verdict", outcome.verdict.name.lowercase())?.increment()
                return ResponseEntity.ok(
                    ScreenView(outcome.verdict.name.lowercase(), outcome.reasons, current.stage.wireValue, taskId),
                )
            }
            else -> {}
        }
        counter("deal.prospects.screens", "verdict", outcome.verdict.name.lowercase())?.increment()
        val stage = prospects.load(id, TenantScope.User(userId))?.stage?.wireValue ?: current.stage.wireValue
        return ResponseEntity.ok(ScreenView(outcome.verdict.name.lowercase(), outcome.reasons, stage, null))
    }

    /**
     * `POST /api/v1/prospects/{id}/agent-screen` — the on-demand path of the ADR-0005 agent
     * workflow: the sidecar drafts a screening memo, jev judges it, and a proceed verdict opens
     * the platform's own `/screen` review task server-side. Members call it for their own
     * tenant's prospects; a viewer sees 404 like every other write here. A sidecar that is
     * down, unconfigured, or flag-off answers 503; its own 4xx surfaces as 502.
     */
    @PostMapping("/api/v1/prospects/{id}/agent-screen")
    fun agentScreen(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val current =
            prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, current.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val result =
            try {
                agents.run(
                    "screening-dd",
                    mapOf(
                        "prospect_id" to id.toString(),
                        "tenant_id" to current.prospect.tenantId.toString(),
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
        counter("deal.prospects.agent_screens")?.increment()
        return ResponseEntity.ok(result)
    }

    /**
     * `POST /api/v1/prospects/{id}/agent-ic-memo` — drafts the IC memo through the sidecar
     * (ADR-0005 F5): the drafter writes, jev gates completeness/thesis/evidence, and a passing
     * memo opens the prospect's `ic-review` approval task — but only while the prospect stands
     * at ic-review; elsewhere the judged draft stays in `agent_run` and nothing advances. Same
     * auth and error contract as `agentScreen`.
     */
    @PostMapping("/api/v1/prospects/{id}/agent-ic-memo")
    fun agentIcMemo(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = userId(jwt) ?: return ResponseEntity.notFound().build()
        val current =
            prospects.load(id, TenantScope.User(userId)) ?: return ResponseEntity.notFound().build()
        val role = roleIn(userId, current.prospect.tenantId) ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val result =
            try {
                agents.run(
                    "ic-memo",
                    mapOf(
                        "prospect_id" to id.toString(),
                        "tenant_id" to current.prospect.tenantId.toString(),
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
        counter("deal.prospects.agent_ic_memos")?.increment()
        return ResponseEntity.ok(result)
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
        // A rule row that fails to parse is REVIEW, never a silent pass: writes are validated at
        // the boundary, so an unreadable row means out-of-band damage a person has to look at.
        val outcomes =
            rows.map { row ->
                row.name to
                    runCatching {
                        val fields: Map<String, List<String>> =
                            json.readValue(
                                row.criteria,
                                object : com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {},
                            )
                        ScreeningCriteria.parse(fields).evaluate(prospect)
                    }.getOrElse {
                        ScreeningOutcome(ScreeningVerdict.REVIEW, listOf("the stored criteria could not be read"))
                    }
            }
        return combine(outcomes)
    }

    /**
     * Whether the request's prospect fields fit their bounds — unbounded text is how one request
     * becomes an oversized write; blank names and the domain's own requires stay the machine's job.
     */
    private fun fieldsBounded(
        name: String,
        sector: String?,
        region: String?,
        sourceRef: String?,
        description: String?,
    ): Boolean =
        name.isNotBlank() && name.length <= NAME_LIMIT &&
            sector.fits() && region.fits() && sourceRef.fits() && (description == null || description.length <= DESCRIPTION_LIMIT)

    private fun String?.fits() = this == null || length <= FIELD_LIMIT

    /**
     * The events that end or redirect an approval task — the gate decisions only `approver`/`admin`
     * may post. `assigned` routes the task and `resubmitted` is already requester-locked by the
     * machine, so neither is a decision.
     */
    private fun TaskEvent.isGateDecision() =
        this is TaskEvent.Approved || this is TaskEvent.Rejected || this is TaskEvent.ReworkRequested || this is TaskEvent.Cancelled

    private fun roleIn(
        userId: UUID,
        tenantId: UUID,
    ): TenantRole? = tenants.tenantsOf(userId).firstOrNull { it.tenantId == tenantId }?.role

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject!!) }.getOrNull()

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

    data class DdEvidenceRequest(
        val workstream: String,
        val summary: String,
        val correlationId: UUID? = null,
    )

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

    data class RetireRequest(
        val tenantId: UUID,
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
