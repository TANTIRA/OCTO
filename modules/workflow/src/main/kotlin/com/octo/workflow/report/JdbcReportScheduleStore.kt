package com.octo.workflow.report

import com.octo.workflow.TenantScope
import com.octo.workflow.scoped
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC access to `mesta.report_schedule` (V22). `claimDue` leases rows inside one statement so two
 * pollers never double-fire; `markRun` clears the lease when it advances the cadence.
 */
class JdbcReportScheduleStore(
    private val dataSource: DataSource,
) : ReportSchedules {
    override fun upsert(
        schedule: ReportSchedule,
        scope: TenantScope,
    ): ReportSchedule {
        val sql =
            """
            insert into mesta.report_schedule (id, tenant_id, name, report_type, position_source_type, position_source_id,
                                               measures, parameters, cron, next_run_at, active)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            on conflict (id) do update set
                name = excluded.name, report_type = excluded.report_type,
                position_source_type = excluded.position_source_type, position_source_id = excluded.position_source_id,
                measures = excluded.measures, parameters = excluded.parameters, cron = excluded.cron,
                next_run_at = excluded.next_run_at, active = excluded.active
            returning *
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, schedule.id)
                statement.setObject(2, schedule.tenantId)
                statement.setString(3, schedule.name)
                statement.setString(4, schedule.reportType.wireValue)
                statement.setString(5, schedule.positionSourceType)
                statement.setString(6, schedule.positionSourceId)
                statement.setArray(7, connection.createArrayOf("text", schedule.measures.toTypedArray()))
                statement.setString(8, schedule.parameters)
                statement.setString(9, schedule.cron)
                statement.setObject(10, schedule.nextRunAt.atOffset(java.time.ZoneOffset.UTC))
                statement.setBoolean(11, schedule.active)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.toSchedule()
                }
            }
        }
    }

    override fun load(
        id: UUID,
        scope: TenantScope,
    ): ReportSchedule? =
        dataSource.scoped(scope) { connection ->
            connection.prepareStatement("select * from mesta.report_schedule where id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toSchedule() else null }
            }
        }

    override fun list(
        tenantId: UUID,
        scope: TenantScope,
    ): List<ReportSchedule> =
        dataSource.scoped(scope) { connection ->
            connection
                .prepareStatement("select * from mesta.report_schedule where tenant_id = ? order by created_at")
                .use { statement ->
                    statement.setObject(1, tenantId)
                    statement.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) rows.toSchedule() else null }.toList()
                    }
                }
        }

    override fun claimDue(
        now: Instant,
        lease: Duration,
    ): List<ReportSchedule> {
        // The lease move is the claim: a row becomes visible to other pollers again only after it lapses.
        val sql =
            """
            update mesta.report_schedule set claimed_until = ?
            where id in (select id from mesta.report_schedule
                         where active and next_run_at <= ? and (claimed_until is null or claimed_until <= ?)
                         for update skip locked)
            returning *
            """.trimIndent()
        return dataSource.scoped(TenantScope.All) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, now.plus(lease).atOffset(java.time.ZoneOffset.UTC))
                statement.setObject(2, now.atOffset(java.time.ZoneOffset.UTC))
                statement.setObject(3, now.atOffset(java.time.ZoneOffset.UTC))
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.toSchedule() else null }.toList()
                }
            }
        }
    }

    override fun markRun(
        id: UUID,
        nextRunAt: Instant,
    ) {
        dataSource.scoped(TenantScope.All) { connection ->
            connection
                .prepareStatement("update mesta.report_schedule set next_run_at = ?, claimed_until = null where id = ?")
                .use { statement ->
                    statement.setObject(1, nextRunAt.atOffset(java.time.ZoneOffset.UTC))
                    statement.setObject(2, id)
                    if (statement.executeUpdate() == 0) throw NoSuchElementException("no report schedule $id")
                }
        }
    }

    private fun ResultSet.toSchedule() =
        ReportSchedule(
            id = getObject("id", UUID::class.java),
            tenantId = getObject("tenant_id", UUID::class.java),
            name = getString("name"),
            reportType = ReportType.fromWireValue(getString("report_type")),
            positionSourceType = getString("position_source_type"),
            positionSourceId = getString("position_source_id"),
            measures = (getArray("measures").array as Array<*>).map { it as String },
            parameters = getString("parameters"),
            cron = getString("cron"),
            nextRunAt = getObject("next_run_at", OffsetDateTime::class.java).toInstant(),
            active = getBoolean("active"),
            claimedUntil = getObject("claimed_until", OffsetDateTime::class.java)?.toInstant(),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )
}
