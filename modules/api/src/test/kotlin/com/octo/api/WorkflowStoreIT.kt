package com.octo.api

import com.octo.workflow.Task
import com.octo.workflow.TaskEvent
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.TaskStatus
import com.octo.workflow.persistence.JdbcTaskStore
import com.octo.workflow.persistence.TaskProvenance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `JdbcTaskStore` against the real V5–V7 schema: create, append with replay validation, load, and two writers
 * racing to decide one task. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class WorkflowStoreIT {
    private val store: JdbcTaskStore by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("octo")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        JdbcTaskStore(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    private val provenance = TaskProvenance("integration-test", UUID.randomUUID())

    @Test
    fun `a task round-trips through create, append and load`() {
        val task = newTask()
        store.create(task, provenance)
        store.append(task.id, TaskEvent.Assigned("alice", at(1), assignee = "bob"), provenance)
        val decided = store.append(task.id, TaskEvent.Approved("bob", at(2), rationale = "numbers tie to the ledger"), provenance)

        assertThat(decided.status).isEqualTo(TaskStatus.APPROVED)
        val loaded = store.load(task.id)
        assertThat(loaded).isEqualTo(decided)
        assertThat(loaded?.task).isEqualTo(task)
        assertThat(loaded?.assignee).isEqualTo("bob")
        assertThat(loaded?.decidedBy).isEqualTo("bob")
    }

    @Test
    fun `a transition the state machine rejects is refused before anything is written`() {
        val task = newTask()
        store.create(task, provenance)

        assertThatThrownBy { store.append(task.id, TaskEvent.Approved("alice", at(1)), provenance) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("segregation of duties")
        assertThat(store.load(task.id)?.status).isEqualTo(TaskStatus.OPEN)
        assertThatThrownBy { store.append(UUID.randomUUID(), TaskEvent.Approved("bob", at(1)), provenance) }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(store.load(UUID.randomUUID())).isNull()
    }

    @Test
    fun `every event type survives the round trip`() {
        val task = newTask()
        store.create(task, provenance)
        store.append(task.id, TaskEvent.ReworkRequested("bob", at(1), "method not stated"), provenance)
        store.append(task.id, TaskEvent.Resubmitted("alice", at(2)), provenance)
        store.append(task.id, TaskEvent.Rejected("bob", at(3), "still not stated"), provenance)
        assertThat(store.load(task.id)?.status).isEqualTo(TaskStatus.REJECTED)

        val review = newTask(TaskKind.REVIEW)
        store.create(review, provenance)
        store.append(review.id, TaskEvent.Completed("alice", at(1)), provenance)
        assertThat(store.load(review.id)?.status).isEqualTo(TaskStatus.COMPLETED)

        val evidence = newTask(TaskKind.EVIDENCE_REQUEST)
        store.create(evidence, provenance)
        store.append(evidence.id, TaskEvent.Cancelled("alice", at(1), "no longer needed"), provenance)
        assertThat(store.load(evidence.id)?.status).isEqualTo(TaskStatus.CANCELLED)
    }

    @Test
    fun `listForSubject returns every task on the subject in creation order`() {
        val first = newTask(subject = "prospect-1")
        val second = newTask(TaskKind.REVIEW, subject = "prospect-1")
        val other = newTask(subject = "prospect-2")
        store.create(first, provenance)
        store.create(second, provenance)
        store.create(other, provenance)
        store.append(first.id, TaskEvent.Approved("bob", at(1)), provenance)

        val tasks = store.listForSubject("valuation-event", "prospect-1")
        assertThat(tasks.map { it.task.id }).containsExactly(first.id, second.id)
        assertThat(tasks.map { it.task.kind }).containsExactly(TaskKind.APPROVAL, TaskKind.REVIEW)
        assertThat(tasks.first().status).isEqualTo(TaskStatus.APPROVED) // replayed state, not just the header
        assertThat(store.listForSubject("valuation-event", "prospect-2").map { it.task.id }).containsExactly(other.id)
        assertThat(store.listForSubject("valuation-event", "nobody")).isEmpty()
    }

    @Test
    fun `openUnlessOpen dedupes per subject and kind, and a terminal task is history`() {
        val subject = "prospect-${UUID.randomUUID().toString().take(8)}"
        val first = newTask(subject = subject)
        val second = newTask(subject = subject)
        val review = newTask(TaskKind.REVIEW, subject = subject)
        val otherSubject = newTask(subject = "prospect-other")

        // the open in flight is the open — a second ask returns it, a different kind or subject does not
        assertThat(store.openUnlessOpen(first, provenance).task.id).isEqualTo(first.id)
        assertThat(store.openUnlessOpen(second, provenance).task.id).isEqualTo(first.id)
        assertThat(store.openUnlessOpen(review, provenance).task.id).isEqualTo(review.id)
        assertThat(store.openUnlessOpen(otherSubject, provenance).task.id).isEqualTo(otherSubject.id)
        assertThat(store.listForSubject("valuation-event", subject).map { it.task.id })
            .containsExactlyInAnyOrder(first.id, review.id)

        // once decided, asking again opens a fresh review rather than resurrecting the old one
        store.append(first.id, TaskEvent.Approved("bob", at(1), "done"), provenance)
        val third = newTask(subject = subject)
        assertThat(store.openUnlessOpen(third, provenance).task.id).isEqualTo(third.id)
    }

    @Test
    fun `two writers racing to open on one subject cannot both mint a task`() {
        val subject = "prospect-${UUID.randomUUID().toString().take(8)}"
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val outcomes =
            listOf(newTask(subject = subject), newTask(subject = subject)).map { candidate ->
                pool.submit<TaskState> {
                    start.await()
                    store.openUnlessOpen(candidate, provenance)
                }
            }
        start.countDown()
        val states = outcomes.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        // both callers get a task back — the same one, because the lock serialized the opens
        assertThat(states.map { it.task.id }.toSet()).hasSize(1)
        assertThat(store.listForSubject("valuation-event", subject)).hasSize(1)
    }

    @Test
    fun `two writers racing to decide one task cannot both succeed`() {
        val task = newTask()
        store.create(task, provenance)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val outcomes =
            listOf("bob", "carol").map { decider ->
                pool.submit<Result<TaskState>> {
                    start.await()
                    runCatching { store.append(task.id, TaskEvent.Approved(decider, at(1)), provenance) }
                }
            }
        start.countDown()
        val results = outcomes.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(results.count { it.isSuccess }).describedAs("exactly one decision lands").isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.load(task.id)?.status).isEqualTo(TaskStatus.APPROVED)
    }

    private fun newTask(
        kind: TaskKind = TaskKind.APPROVAL,
        subject: String = "ve-1",
    ) = Task(UUID.randomUUID(), kind, "valuation-event", subject, "alice", at(0))

    private companion object {
        val T0: Instant = Instant.parse("2026-09-24T09:00:00Z")

        fun at(minutes: Long): Instant = T0.plusSeconds(minutes * 60)

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("octo")
                .withUsername("octo")
                .withPassword("octo")
    }
}
