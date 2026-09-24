package com.mesta.asset.workflow.persistence

import com.mesta.asset.workflow.Task
import com.mesta.asset.workflow.TaskEvent
import com.mesta.asset.workflow.TaskKind
import com.mesta.asset.workflow.TaskState
import com.mesta.asset.workflow.next
import com.mesta.asset.workflow.replay
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Where a task or event came from, for the audit columns V5 requires. Events carry only the correlation id. */
data class TaskProvenance(
    val sourceSystem: String,
    val correlationId: UUID,
)

/**
 * JDBC access to `mesta.workflow_task` and `mesta.workflow_task_event` (V5, V7). A task's state is never stored:
 * [load] replays its events through the state machine, and [append] validates a new event against that replay
 * before inserting it. Both run under the per-task advisory lock V7's trigger takes, so two writers to one task
 * serialize and neither can interleave an event into a history the other has already replayed.
 */
class JdbcTaskStore(
    private val dataSource: DataSource,
) {
    fun create(
        task: Task,
        provenance: TaskProvenance,
    ) {
        val sql =
            """
            insert into mesta.workflow_task (id, kind, subject_type, subject_id, requested_by, created_at, source_system, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, task.id)
                statement.setString(2, task.kind.wireValue)
                statement.setString(3, task.subjectType)
                statement.setString(4, task.subjectId)
                statement.setString(5, task.requestedBy)
                statement.setObject(6, task.createdAt.atOffset(ZoneOffset.UTC))
                statement.setString(7, provenance.sourceSystem)
                statement.setObject(8, provenance.correlationId)
                statement.executeUpdate()
            }
        }
    }

    /** The task's state after every stored event, or null when no task has that id. */
    fun load(taskId: UUID): TaskState? = dataSource.connection.use { connection -> replayLocked(connection, taskId) }

    /**
     * Validates [event] against the task's current state and stores it, in one transaction. Throws
     * [IllegalArgumentException] for a transition the state machine rejects, or [NoSuchElementException] for an
     * unknown task; nothing is written in either case.
     */
    fun append(
        taskId: UUID,
        event: TaskEvent,
        provenance: TaskProvenance,
    ): TaskState {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = replayLocked(connection, taskId) ?: throw NoSuchElementException("no task $taskId")
                val after = before.next(event)
                insertEvent(connection, taskId, event, provenance)
                connection.commit()
                return after
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun replayLocked(
        connection: Connection,
        taskId: UUID,
    ): TaskState? {
        connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended('mesta.workflow_task:' || ?::text, 0))").use {
            it.setObject(1, taskId)
            it.executeQuery().close()
        }
        val task = selectTask(connection, taskId) ?: return null
        return replay(task, selectEvents(connection, taskId))
    }

    private fun selectTask(
        connection: Connection,
        taskId: UUID,
    ): Task? =
        connection
            .prepareStatement(
                "select kind, subject_type, subject_id, requested_by, created_at from mesta.workflow_task where id = ?",
            ).use {
                it.setObject(1, taskId)
                it.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    Task(
                        id = taskId,
                        kind = TaskKind.entries.first { kind -> kind.wireValue == rows.getString(1) },
                        subjectType = rows.getString(2),
                        subjectId = rows.getString(3),
                        requestedBy = rows.getString(4),
                        createdAt = rows.getObject(5, OffsetDateTime::class.java).toInstant(),
                    )
                }
            }

    /** Events in append order: V7's seq, because occurred_at can tie. */
    private fun selectEvents(
        connection: Connection,
        taskId: UUID,
    ): List<TaskEvent> =
        connection
            .prepareStatement(
                "select event_type, actor, assignee, rationale, occurred_at from mesta.workflow_task_event where task_id = ? order by seq",
            ).use { statement ->
                statement.setObject(1, taskId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val actor = rows.getString(2)
                            val at = rows.getObject(5, OffsetDateTime::class.java).toInstant()
                            add(
                                when (val type = rows.getString(1)) {
                                    "assigned" -> TaskEvent.Assigned(actor, at, rows.getString(3))
                                    "approved" -> TaskEvent.Approved(actor, at, rows.getString(4))
                                    "rejected" -> TaskEvent.Rejected(actor, at, rows.getString(4))
                                    "rework-requested" -> TaskEvent.ReworkRequested(actor, at, rows.getString(4))
                                    "resubmitted" -> TaskEvent.Resubmitted(actor, at)
                                    "completed" -> TaskEvent.Completed(actor, at, rows.getString(4))
                                    "cancelled" -> TaskEvent.Cancelled(actor, at, rows.getString(4))
                                    else -> error("unknown workflow event type $type")
                                },
                            )
                        }
                    }
                }
            }

    private fun insertEvent(
        connection: Connection,
        taskId: UUID,
        event: TaskEvent,
        provenance: TaskProvenance,
    ) {
        val (type, assignee, rationale) =
            when (event) {
                is TaskEvent.Assigned -> Triple("assigned", event.assignee, null)
                is TaskEvent.Approved -> Triple("approved", null, event.rationale)
                is TaskEvent.Rejected -> Triple("rejected", null, event.rationale)
                is TaskEvent.ReworkRequested -> Triple("rework-requested", null, event.rationale)
                is TaskEvent.Resubmitted -> Triple("resubmitted", null, null)
                is TaskEvent.Completed -> Triple("completed", null, event.rationale)
                is TaskEvent.Cancelled -> Triple("cancelled", null, event.rationale)
            }
        val sql =
            """
            insert into mesta.workflow_task_event (task_id, event_type, actor, assignee, rationale, occurred_at, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, taskId)
            statement.setString(2, type)
            statement.setString(3, event.actor)
            statement.setString(4, assignee)
            statement.setString(5, rationale)
            statement.setObject(6, event.at.atOffset(ZoneOffset.UTC))
            statement.setObject(7, provenance.correlationId)
            statement.executeUpdate()
        }
    }
}
