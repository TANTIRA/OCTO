package com.mesta.asset.recon.matching.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.recon.matching.Break
import com.mesta.asset.recon.matching.IborRecord
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Currency
import java.util.UUID
import javax.sql.DataSource

/** What the runner reads and writes. */
interface ReconciliationStore {
    /** The current ledger events of one source system as [IborRecord]s, dated in [zone]. Superseded rows are excluded. */
    fun iborRecords(
        sourceSystem: String,
        zone: ZoneId,
    ): List<IborRecord>

    /** The task an earlier run opened for this break key, or null. */
    fun existingTask(
        tenantId: UUID,
        brk: Break,
    ): UUID?

    /** Records one finding of [runId]; a break with a task claims V15's one-task slot for its key. Returns the row id. */
    fun record(
        tenantId: UUID,
        runId: UUID,
        brk: Break,
        taskId: UUID?,
        correlationId: UUID,
    ): UUID
}

/** JDBC access to `mesta.reconciliation_break` (V15) and the ledger side of a run, read straight from `mesta.ledger_event`. */
class JdbcReconciliationStore(
    private val dataSource: DataSource,
) : ReconciliationStore {
    private val json = ObjectMapper()

    override fun iborRecords(
        sourceSystem: String,
        zone: ZoneId,
    ): List<IborRecord> {
        // V1 keys (source_system, external_id) once per chain: a correction carries no external id and supersedes
        // the keyed root, so the id of the current event is the root's, found by walking the chain forward.
        val sql =
            """
            with recursive chain as (
                select e.id, e.external_id as root_external_id
                from mesta.ledger_event e where e.source_system = ? and e.external_id is not null
                union all
                select s.id, c.root_external_id from mesta.ledger_event s join chain c on s.supersedes_id = c.id)
            select e.id, e.source_system, coalesce(c.root_external_id, e.external_id) as external_id,
                   e.monetary_amount, e.currency_code, e.occurred_at
            from mesta.ledger_event e left join chain c on c.id = e.id
            where e.source_system = ? and not exists (select 1 from mesta.ledger_event s where s.supersedes_id = e.id)
            order by e.occurred_at, e.id
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, sourceSystem)
                statement.setString(2, sourceSystem)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows else null }
                        .map {
                            IborRecord(
                                id = it.getObject("id", UUID::class.java),
                                sourceSystem = it.getString("source_system"),
                                externalId = it.getString("external_id"),
                                amount = it.getBigDecimal("monetary_amount"),
                                currency = Currency.getInstance(it.getString("currency_code")),
                                date =
                                    it
                                        .getObject("occurred_at", OffsetDateTime::class.java)
                                        .toInstant()
                                        .atZone(zone)
                                        .toLocalDate(),
                            )
                        }.toList()
                }
            }
        }
    }

    override fun existingTask(
        tenantId: UUID,
        brk: Break,
    ): UUID? {
        val sql =
            """
            select task_id from mesta.reconciliation_break
            where tenant_id = ? and kind = ? and source_system = ? and source_ref is not distinct from ? and ledger_event_id is not distinct from ?
              and task_id is not null
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, brk.kind.wireValue)
                statement.setString(3, brk.sourceSystem)
                statement.setString(4, brk.sourceRef)
                statement.setObject(5, brk.ledgerEventId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("task_id", UUID::class.java) else null }
            }
        }
    }

    override fun record(
        tenantId: UUID,
        runId: UUID,
        brk: Break,
        taskId: UUID?,
        correlationId: UUID,
    ): UUID {
        val sql =
            """
            insert into mesta.reconciliation_break (tenant_id, run_id, kind, source_system, source_ref, ledger_event_id, detail, task_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            returning id
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setObject(2, runId)
                statement.setString(3, brk.kind.wireValue)
                statement.setString(4, brk.sourceSystem)
                statement.setString(5, brk.sourceRef)
                statement.setObject(6, brk.ledgerEventId)
                statement.setString(7, json.writeValueAsString(brk.detail))
                statement.setObject(8, taskId)
                statement.setObject(9, correlationId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getObject("id", UUID::class.java)
                }
            }
        }
    }
}
