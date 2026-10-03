package com.octo.api

import com.octo.api.access.TenantRole
import com.octo.workflow.TaskEvent
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.persistence.TaskProvenance
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * The events that end or redirect an approval task — the gate decisions only `approver` may post.
 * `assigned` routes the task and `resubmitted` is already requester-locked by the machine, so
 * neither is a decision. Shared by every controller that lets a person work a task: a private copy
 * in each route would let one gate drift from the others.
 */
internal fun TaskEvent.isGateDecision() =
    this is TaskEvent.Approved || this is TaskEvent.Rejected || this is TaskEvent.ReworkRequested || this is TaskEvent.Cancelled

/**
 * Whether [this] role may post [event] on a task of [kind]. A gate decision on an `approval` task
 * is the APPROVER's duty alone: administration is segregated from approval duties (see
 * `TenantRole.ADMIN`; data-security-governance.md), so an admin who controls membership must not
 * also decide a gate. Working events — assigning, resubmitting, completing — stay open to members,
 * and the state machine still applies its own actor and transition rules after this check.
 */
internal fun TenantRole.mayPost(
    kind: TaskKind,
    event: TaskEvent,
): Boolean = kind != TaskKind.APPROVAL || !event.isGateDecision() || this == TenantRole.APPROVER

/**
 * Appends [event] to a task and maps the outcome the way every task route does: a vanished task
 * answers 404, a transition the state machine rejects answers 409, and the accepted state goes
 * through [view]. Callers own the actor, subject-binding, and role checks that precede the append.
 */
internal fun <T> decideTask(
    taskId: UUID,
    event: TaskEvent,
    correlationId: UUID?,
    append: (UUID, TaskEvent, TaskProvenance) -> TaskState,
    view: (TaskState) -> T,
): ResponseEntity<T> {
    val after =
        try {
            append(taskId, event, TaskProvenance("api", correlationId ?: UUID.randomUUID()))
        } catch (_: NoSuchElementException) {
            return ResponseEntity.notFound().build()
        } catch (_: IllegalArgumentException) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    return ResponseEntity.ok(view(after))
}
