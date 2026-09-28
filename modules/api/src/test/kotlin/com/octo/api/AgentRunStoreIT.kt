package com.octo.api

import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.agents.persistence.AgentRunRecord
import com.octo.api.agents.persistence.AgentRunStatus
import com.octo.api.agents.persistence.JdbcAgentRunsStore
import com.octo.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * `JdbcAgentRunsStore` against the real schema: a run opens `running`, finishes exactly once,
 * dedupes on its caller's `run_key`, and a human outcome lands once. Runs as the table owner;
 * the RLS side is covered in EntityScopingIT. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentRunStoreIT {
    private val tenantId = UUID.randomUUID()
    private val runId = UUID.randomUUID()

    private val dataSource by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("octo")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    private val store by lazy { JdbcAgentRunsStore(dataSource) }

    @BeforeEach
    fun seed() {
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "insert into octo.tenant (id, slug, display_name, source_system, correlation_id) " +
                        "values ('$tenantId', 't-${tenantId.toString().take(8)}', 'T', 'test', gen_random_uuid())",
                )
            }
        }
    }

    @Test
    fun `a run records running, finishes once, and dedupes on run_key`() {
        val id = store.record(record(), TenantScope.All)
        assertThat(id).isNotNull()

        // Same logical run re-triggered: no second row, the original id is what the retry reads.
        assertThat(store.record(record(), TenantScope.All)).isNull()
        assertThat(store.loadByKey(tenantId, "key-$runId", TenantScope.All)!!.id).isEqualTo(id)

        val opened = store.load(id!!, TenantScope.All)!!
        assertThat(opened.status).isEqualTo(AgentRunStatus.RUNNING)
        assertThat(opened.finishedAt).isNull()
        assertThat(opened.workflow).isEqualTo("screening-dd")
        assertThat(opened.input).contains("prospect-1")

        assertThat(
            store.finish(
                id,
                AgentRunStatus.COMPLETED,
                output = """{"memo":"thesis fit"}""",
                verdict = """{"advance":{"noul":0.83}}""",
                error = null,
                scope = TenantScope.All,
            ),
        ).isTrue()
        // A finished row never rewrites — a second finish, or a finish of another status, is false.
        assertThat(
            store.finish(id, AgentRunStatus.FAILED, null, null, "late", TenantScope.All),
        ).isFalse()

        val done = store.load(id, TenantScope.All)!!
        assertThat(done.status).isEqualTo(AgentRunStatus.COMPLETED)
        assertThat(done.verdict).contains("0.83")
        assertThat(done.finishedAt).isNotNull()

        // Human outcome lands once; a rewrite is refused.
        assertThat(
            store.recordOutcome(id, """{"decision":"advanced","decided_by":"sub-1"}""", TenantScope.All),
        ).isTrue()
        assertThat(
            store.recordOutcome(id, """{"decision":"rejected","decided_by":"sub-2"}""", TenantScope.All),
        ).isFalse()
        assertThat(store.load(id, TenantScope.All)!!.humanOutcome).contains("advanced")
    }

    @Test
    fun `a refused run still records its verdict lineage and list reads newest first`() {
        val id = store.record(record(runKey = "refused-$runId"), TenantScope.All)!!
        assertThat(
            store.finish(
                id,
                AgentRunStatus.REFUSED,
                output = null,
                verdict = """{"answerable":{"noul":0.12}}""",
                error = "insufficient record",
                scope = TenantScope.All,
            ),
        ).isTrue()
        store.finish(
            store.record(record(runKey = "completed-$runId"), TenantScope.All)!!,
            AgentRunStatus.COMPLETED,
            null,
            null,
            null,
            TenantScope.All,
        )

        val all = store.list(tenantId, "prospect", "prospect-1", 10, TenantScope.All)
        assertThat(all.map { it.runKey }).containsExactly("completed-$runId", "refused-$runId")
        assertThat(all.first().status).isEqualTo(AgentRunStatus.COMPLETED)
        assertThat(all.last().error).isEqualTo("insufficient record")

        // A subject filter that matches nothing is an empty page, not an error.
        assertThat(store.list(tenantId, "report", "nope", 10, TenantScope.All)).isEmpty()
    }

    private fun record(runKey: String = "key-$runId") =
        AgentRunRecord(
            tenantId = tenantId,
            workflow = "screening-dd",
            runKey = runKey,
            subjectType = "prospect",
            subjectId = "prospect-1",
            actor = "agent-principal",
            input = """{"prospect_id":"prospect-1","lineage":{"events":3}}""",
            models = """{"drafter":"deepseek/deepseek-v4.1-flash","judge":"typesafe/jev-1.13"}""",
            thresholds = """{"proceed":0.7}""",
            requestIds = """{"judge":"req-1"}""",
            provenance = AccessProvenance("test", UUID.randomUUID()),
        )

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
