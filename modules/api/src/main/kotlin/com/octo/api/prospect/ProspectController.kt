package com.octo.api.prospect

import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.TenantScope
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.dealsourcing.persistence.ProspectStore
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
    private val tenants: TenantDirectory,
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
        val event =
            eventOf(body, current, jwt.subject) ?: return ResponseEntity.badRequest().build()
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
    /** The request names only where to land; `from` is the replayed stage, never client-asserted. */
    private fun eventOf(
        body: TransitionRequest,
        current: ProspectState,
        actor: String,
    ): ProspectEvent? {
        val to = runCatching { ProspectStage.fromWireValue(body.to) }.getOrNull() ?: return null
        val at = Instant.now()
        return when (to) {
            ProspectStage.PASSED ->
                body.rationale
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ProspectEvent.Passed(actor, at, current.stage, it) }
            ProspectStage.INVESTED ->
                body.rationale
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ProspectEvent.Invested(actor, at, it) }
            else -> ProspectEvent.Advanced(actor, at, current.stage, to)
        }
    }

    private fun roleIn(
        userId: UUID,
        tenantId: UUID,
    ): TenantRole? = tenants.tenantsOf(userId).firstOrNull { it.tenantId == tenantId }?.role

    private fun userId(jwt: Jwt) = runCatching { UUID.fromString(jwt.subject) }.getOrNull()

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

        val correlationId: UUID? = null,
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
