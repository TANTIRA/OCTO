package com.mesta.asset.workflow

import java.time.Instant
import java.util.UUID

/** What a task asks a person to do. It decides how the task can end. */
enum class TaskKind(
    val wireValue: String,
) {
    /** Decide something someone else requested: an override, disclosure, IC submission, external report, email, model promotion. */
    APPROVAL("approval"),

    /** Look at an output and mark it done, e.g. the manual review an `Unknown` screening result creates. */
    REVIEW("review"),

    /** Obtain missing evidence and mark it done. */
    EVIDENCE_REQUEST("evidence-request"),
}

enum class TaskStatus(
    val terminal: Boolean,
) {
    OPEN(false),
    IN_REWORK(false),
    APPROVED(true),
    REJECTED(true),
    COMPLETED(true),
    CANCELLED(true),
}

/** A request for human action on one subject, identified by its type and id for lineage. */
data class Task(
    val id: UUID,
    val kind: TaskKind,
    val subjectType: String,
    val subjectId: String,
    val requestedBy: String,
    val createdAt: Instant,
) {
    init {
        require(subjectType.isNotBlank() && subjectId.isNotBlank()) { "a task must name its subject" }
        require(requestedBy.isNotBlank()) { "a task must name who requested it" }
    }
}

/** A fact about a task. The task's state is never stored, only derived from these, like positions from the ledger. */
sealed interface TaskEvent {
    val actor: String
    val at: Instant

    data class Assigned(
        override val actor: String,
        override val at: Instant,
        val assignee: String,
    ) : TaskEvent

    data class Approved(
        override val actor: String,
        override val at: Instant,
        val rationale: String? = null,
    ) : TaskEvent

    data class Rejected(
        override val actor: String,
        override val at: Instant,
        val rationale: String,
    ) : TaskEvent {
        init {
            require(rationale.isNotBlank()) { "a rejection needs a rationale" }
        }
    }

    data class ReworkRequested(
        override val actor: String,
        override val at: Instant,
        val rationale: String,
    ) : TaskEvent {
        init {
            require(rationale.isNotBlank()) { "a rework request needs a rationale" }
        }
    }

    data class Resubmitted(
        override val actor: String,
        override val at: Instant,
    ) : TaskEvent

    data class Completed(
        override val actor: String,
        override val at: Instant,
        val rationale: String? = null,
    ) : TaskEvent

    data class Cancelled(
        override val actor: String,
        override val at: Instant,
        val rationale: String,
    ) : TaskEvent {
        init {
            require(rationale.isNotBlank()) { "a cancellation needs a rationale" }
        }
    }
}

data class TaskState(
    val task: Task,
    val status: TaskStatus,
    val assignee: String?,
    val decidedBy: String?,
    val lastEventAt: Instant,
)

/** A new task, before any event. */
fun opened(task: Task): TaskState = TaskState(task, TaskStatus.OPEN, assignee = null, decidedBy = null, lastEventAt = task.createdAt)

/** The state after [events], in order. Throws on any transition [next] rejects. */
fun replay(
    task: Task,
    events: List<TaskEvent>,
): TaskState = events.fold(opened(task)) { state, event -> state.next(event) }

/**
 * Applies one event under the rules in `data-security-governance.md` and `deal-sourcing-workflow.md`:
 * nobody decides or is assigned an approval they requested, only the requester resubmits after rework,
 * and nothing follows a terminal status.
 */
fun TaskState.next(event: TaskEvent): TaskState {
    require(!status.terminal) { "task ${task.id} is already $status" }
    require(event.actor.isNotBlank()) { "every event needs an actor" }
    require(!event.at.isBefore(lastEventAt)) { "events must be in time order" }
    val approval = task.kind == TaskKind.APPROVAL

    val after =
        when (event) {
            is TaskEvent.Assigned -> {
                require(!(approval && event.assignee == task.requestedBy)) { "an approval cannot be assigned to its requester" }
                copy(assignee = event.assignee)
            }
            is TaskEvent.Approved, is TaskEvent.Rejected, is TaskEvent.ReworkRequested -> {
                require(approval) { "only approval tasks are approved, rejected or sent back; complete a ${task.kind} task" }
                require(status == TaskStatus.OPEN) { "task ${task.id} is $status, not open for a decision" }
                require(event.actor != task.requestedBy) {
                    "segregation of duties: ${event.actor} requested this approval and cannot decide it"
                }
                when (event) {
                    is TaskEvent.Approved -> copy(status = TaskStatus.APPROVED, decidedBy = event.actor)
                    is TaskEvent.Rejected -> copy(status = TaskStatus.REJECTED, decidedBy = event.actor)
                    else -> copy(status = TaskStatus.IN_REWORK)
                }
            }
            is TaskEvent.Resubmitted -> {
                require(status == TaskStatus.IN_REWORK) { "only a task sent back for rework can be resubmitted" }
                require(event.actor == task.requestedBy) { "only the requester resubmits after rework" }
                copy(status = TaskStatus.OPEN)
            }
            is TaskEvent.Completed -> {
                require(!approval) { "an approval ends in a decision, not completion" }
                require(status == TaskStatus.OPEN) { "task ${task.id} is $status, not open" }
                copy(status = TaskStatus.COMPLETED, decidedBy = event.actor)
            }
            is TaskEvent.Cancelled -> copy(status = TaskStatus.CANCELLED, decidedBy = event.actor)
        }
    return after.copy(lastEventAt = event.at)
}
