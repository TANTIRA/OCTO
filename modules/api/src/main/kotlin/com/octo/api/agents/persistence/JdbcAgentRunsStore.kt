package com.octo.api.agents.persistence

import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC access to `octo.agent_run` (V33). Everything runs under the caller's [TenantScope] like
 * every tenant table — RLS alone keeps a misdirected write inside its own boundary.
 */
class JdbcAgentRunsStore(
    private val dataSource: DataSource,
) : AgentRuns {
    override fun record(
        record: AgentRunRecord,
        scope: TenantScope,
    ): UUID? =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    """
                    insert into octo.agent_run
                        (tenant_id, workflow, run_key, subject_type, subject_id, status, actor,
                         input, models, thresholds, request_ids, source_system, correlation_id)
                    values (?, ?, ?, ?, ?, 'running', ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                    on conflict (tenant_id, run_key) do nothing
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, record.tenantId)
                    statement.setString(2, record.workflow)
                    statement.setString(3, record.runKey)
                    statement.setString(4, record.subjectType)
                    statement.setString(5, record.subjectId)
                    statement.setString(6, record.actor)
                    statement.setString(7, record.input)
                    statement.setString(8, record.models)
                    statement.setString(9, record.thresholds)
                    statement.setString(10, record.requestIds)
                    statement.setString(11, record.provenance.sourceSystem)
                    statement.setObject(12, record.provenance.correlationId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) null else rows.getObject(1, UUID::class.java)
                    }
                }
        }

    override fun finish(
        id: UUID,
        status: AgentRunStatus,
        output: String?,
        verdict: String?,
        error: String?,
        scope: TenantScope,
    ): Boolean =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    """
                    update octo.agent_run
                    set status = ?, output = ?::jsonb, verdict = ?::jsonb, error = ?, finished_at = now()
                    where id = ? and status = 'running'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, status.wireValue)
                    statement.setString(2, output)
                    statement.setString(3, verdict)
                    statement.setString(4, error)
                    statement.setObject(5, id)
                    statement.executeUpdate() == 1
                }
        }

    override fun recordOutcome(
        id: UUID,
        outcome: String,
        scope: TenantScope,
    ): Boolean =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    """
                    update octo.agent_run set human_outcome = ?::jsonb
                    where id = ? and human_outcome is null and status <> 'running'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, outcome)
                    statement.setObject(2, id)
                    statement.executeUpdate() == 1
                }
        }

    override fun loadByKey(
        tenantId: UUID,
        runKey: String,
        scope: TenantScope,
    ): AgentRun? =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    "select $COLUMNS from octo.agent_run where tenant_id = ? and run_key = ?",
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setString(2, runKey)
                    statement.executeQuery().use { rows -> if (!rows.next()) null else rows.toRun() }
                }
        }

    override fun load(
        id: UUID,
        scope: TenantScope,
    ): AgentRun? =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement("select $COLUMNS from octo.agent_run where id = ?")
                .use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rows -> if (!rows.next()) null else rows.toRun() }
                }
        }

    override fun list(
        tenantId: UUID,
        subjectType: String?,
        subjectId: String?,
        limit: Int,
        scope: TenantScope,
    ): List<AgentRun> =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement(
                    """
                    select $COLUMNS from octo.agent_run
                    where tenant_id = ?
                      and (?::text is null or subject_type = ?)
                      and (?::text is null or subject_id = ?)
                    order by created_at desc
                    limit ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.setString(2, subjectType)
                    statement.setString(3, subjectType)
                    statement.setString(4, subjectId)
                    statement.setString(5, subjectId)
                    statement.setInt(6, limit)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.toRun()) }
                    }
                }
        }

    private companion object {
        const val COLUMNS =
            "id, tenant_id, workflow, run_key, subject_type, subject_id, status, actor, " +
                "input::text, output::text, verdict::text, models::text, thresholds::text, " +
                "request_ids::text, error, human_outcome::text, created_at, finished_at"
    }
}

private fun ResultSet.toRun() =
    AgentRun(
        id = getObject("id", UUID::class.java),
        tenantId = getObject("tenant_id", UUID::class.java),
        workflow = getString("workflow"),
        runKey = getString("run_key"),
        subjectType = getString("subject_type"),
        subjectId = getString("subject_id"),
        status =
            AgentRunStatus.valueOf(
                getString("status").replace('-', '_').uppercase(),
            ),
        actor = getString("actor"),
        input = getString("input"),
        output = getString("output"),
        verdict = getString("verdict"),
        models = getString("models"),
        thresholds = getString("thresholds"),
        requestIds = getString("request_ids"),
        error = getString("error"),
        humanOutcome = getString("human_outcome"),
        createdAt = getTimestamp("created_at").toInstant(),
        finishedAt = getTimestamp("finished_at")?.toInstant(),
    )
