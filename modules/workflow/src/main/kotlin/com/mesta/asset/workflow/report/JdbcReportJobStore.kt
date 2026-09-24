package com.mesta.asset.workflow.report

import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** JDBC access to `mesta.report_job` (V13). Every transition is one statement; the trigger refuses anything out of order. */
class JdbcReportJobStore(
    private val dataSource: DataSource,
) : ReportJobs {
    override fun submit(request: ReportRequest): ReportJob {
        val sql =
            """
            insert into mesta.report_job (tenant_id, report_type, position_source_type, position_source_id, measures, parameters,
                                          requested_by, correlation_id)
            values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            returning *
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, request.tenantId)
                statement.setString(2, request.type.wireValue)
                statement.setString(3, request.positionSourceType)
                statement.setString(4, request.positionSourceId)
                statement.setArray(5, connection.createArrayOf("text", request.measures.toTypedArray()))
                statement.setString(6, request.parameters)
                statement.setString(7, request.requestedBy)
                statement.setObject(8, request.correlationId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.toJob()
                }
            }
        }
    }

    override fun load(id: UUID): ReportJob? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select * from mesta.report_job where id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toJob() else null }
            }
        }

    override fun claimNext(): ReportJob? {
        // skip locked: a job another runner holds in its claim transaction is passed over, never double-claimed.
        val sql =
            """
            update mesta.report_job set status = 'executing'
            where id = (select id from mesta.report_job where status = 'new' order by created_at limit 1 for update skip locked)
            returning *
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows -> if (rows.next()) rows.toJob() else null }
            }
        }
    }

    override fun complete(
        id: UUID,
        result: String,
        artifactSha256: String?,
    ): ReportJob =
        transition(id, "status = 'done', result = ?::jsonb, artifact_sha256 = ?") {
            it.setString(1, result)
            it.setString(2, artifactSha256)
            it.setObject(3, id)
        }

    override fun fail(
        id: UUID,
        error: String,
    ): ReportJob =
        transition(id, "status = 'error', error = ?") {
            it.setString(1, error)
            it.setObject(2, id)
        }

    private fun transition(
        id: UUID,
        assignment: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): ReportJob =
        dataSource.connection.use { connection: Connection ->
            connection.prepareStatement("update mesta.report_job set $assignment where id = ? returning *").use { statement ->
                bind(statement)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) throw NoSuchElementException("no report job $id")
                    rows.toJob()
                }
            }
        }

    private fun ResultSet.toJob() =
        ReportJob(
            id = getObject("id", UUID::class.java),
            request =
                ReportRequest(
                    tenantId = getObject("tenant_id", UUID::class.java),
                    type = ReportType.fromWireValue(getString("report_type")),
                    positionSourceType = getString("position_source_type"),
                    positionSourceId = getString("position_source_id"),
                    measures = (getArray("measures").array as Array<*>).map { it as String },
                    parameters = getString("parameters"),
                    requestedBy = getString("requested_by"),
                    correlationId = getObject("correlation_id", UUID::class.java),
                ),
            status = JobStatus.fromWireValue(getString("status")),
            result = getString("result"),
            error = getString("error"),
            artifactSha256 = getString("artifact_sha256"),
            approvalTaskId = getObject("approval_task_id", UUID::class.java),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )
}
