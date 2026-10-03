package com.octo.api

import com.octo.workflow.TaskEvent
import com.octo.workflow.TaskState
import java.time.Instant
import java.util.UUID

/**
 * One event for a subject-scoped task endpoint (`/prospects/{id}/tasks/{taskId}`,
 * `/reports/{id}/tasks/{taskId}`), named by its `workflow_task_event.event_type` wire
 * value. `assignee` is required by `assigned`; `rationale` by `rejected`,
 * `rework-requested` and `cancelled`; anything else missing answers 400 before the
 * state machine sees it.
 */
data class TaskEventRequest(
    val event: String,
    val rationale: String? = null,
    val assignee: String? = null,
    val correlationId: UUID? = null,
) {
    fun toEvent(actor: String): TaskEvent? {
        // Rationale and assignee land in the append-only task-event log — an oversized one
        // can never be removed, so it is refused here with a 400 (#504).
        if (rationale != null && rationale.length > LONG_TEXT_LIMIT) return null
        if (assignee != null && assignee.length > SHORT_TEXT_LIMIT) return null
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

/**
 * The events that end or redirect an approval task — the gate decisions only an
 * `approver` may post. `assigned` routes the task and `resubmitted` is already
 * requester-locked by the machine, so neither is a decision.
 */
fun TaskEvent.isGateDecision() =
    this is TaskEvent.Approved || this is TaskEvent.Rejected || this is TaskEvent.ReworkRequested || this is TaskEvent.Cancelled

data class TaskView(
    val taskId: UUID,
    val kind: String,
    val status: String,
    val assignee: String?,
    val decidedBy: String?,
    val requestedBy: String,
)

fun TaskState.taskView() =
    TaskView(
        taskId = task.id,
        kind = task.kind.wireValue,
        status = status.name.lowercase(),
        assignee = assignee,
        decidedBy = decidedBy,
        requestedBy = task.requestedBy,
    )
