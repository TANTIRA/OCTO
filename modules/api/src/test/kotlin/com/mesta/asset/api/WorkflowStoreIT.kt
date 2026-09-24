package com.mesta.asset.api

import com.mesta.asset.workflow.Task
import com.mesta.asset.workflow.TaskEvent
import com.mesta.asset.workflow.TaskKind
import com.mesta.asset.workflow.TaskState
import com.mesta.asset.workflow.TaskStatus
import com.mesta.asset.workflow.persistence.JdbcTaskStore
import com.mesta.asset.workflow.persistence.TaskProvenance
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
            .schemas("mesta")
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

    private fun newTask(kind: TaskKind = TaskKind.APPROVAL) = Task(UUID.randomUUID(), kind, "valuation-event", "ve-1", "alice", at(0))

    private companion object {
        val T0: Instant = Instant.parse("2026-09-24T09:00:00Z")

        fun at(minutes: Long): Instant = T0.plusSeconds(minutes * 60)

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")
    }
}
