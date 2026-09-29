package com.octo.recon.matching.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.persistence.TenantScope
import com.octo.persistence.admits
import com.octo.persistence.scoped
import com.octo.recon.matching.Break
import com.octo.recon.matching.IborRecord
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Currency
import java.util.UUID
import javax.sql.DataSource

/** One reconciliation request may carry at most this many source records — the bound `IMPORT_BATCH_LIMIT` puts on a prospect import. */
const val RECONCILIATION_BATCH_LIMIT = 500

/** What the runner reads and writes. */
interface ReconciliationStore {
    /** The current ledger events of one source system as [IborRecord]s, dated in [zone]. Superseded rows are excluded. */
    fun iborRecords(
        tenantId: UUID,
        sourceSystem: String,
        zone: ZoneId,
        scope: TenantScope,
    ): List<IborRecord>

    /** The task an earlier run opened for this break key, or null. */
    fun existingTask(
        tenantId: UUID,
        brk: Break,
        scope: TenantScope,
    ): UUID?

    /** Records one finding of [runId]; a break with a task claims V15's one-task slot for its key. Returns the row id. */
    fun record(
        tenantId: UUID,
        runId: UUID,
        brk: Break,
        taskId: UUID?,
        correlationId: UUID,
        scope: TenantScope,
    ): UUID
}

/** JDBC access to `octo.reconciliation_break` (V15) and the ledger side of a run, read straight from `octo.ledger_event`. */
class JdbcReconciliationStore(
    private val dataSource: DataSource,
) : ReconciliationStore {
    private val json = ObjectMapper()

    override fun iborRecords(
        tenantId: UUID,
        sourceSystem: String,
        zone: ZoneId,
        scope: TenantScope,
    ): List<IborRecord> {
        if (!scope.admits(tenantId)) return emptyList()
        // V1 keys (source_system, external_id) once per chain: a correction carries no external id and supersedes
        // the keyed root, so the id of the current event is the root's, found by walking the chain forward.
        val sql =
            """
            with recursive chain as (
                select e.id, e.external_id as root_external_id
                from octo.ledger_event e where e.tenant_id = ? and e.source_system = ? and e.external_id is not null
                union all
                select s.id, c.root_external_id from octo.ledger_event s join chain c on s.supersedes_id = c.id
                where s.tenant_id = ? and s.source_system = ?)
            select e.id, e.source_system, coalesce(c.root_external_id, e.external_id) as external_id,
                   e.monetary_amount, e.currency_code, e.occurred_at
            from octo.ledger_event e left join chain c on c.id = e.id
            where e.tenant_id = ? and e.source_system = ?
              and not exists (select 1 from octo.ledger_event s where s.supersedes_id = e.id
                              and s.tenant_id = e.tenant_id and s.source_system = e.source_system)
            order by e.occurred_at, e.id
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, sourceSystem)
                statement.setObject(3, tenantId)
                statement.setString(4, sourceSystem)
                statement.setObject(5, tenantId)
                statement.setString(6, sourceSystem)
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
        scope: TenantScope,
    ): UUID? {
        if (!scope.admits(tenantId)) return null
        val sql =
            """
            select task_id from octo.reconciliation_break
            where tenant_id = ? and kind = ? and source_system = ? and source_ref is not distinct from ? and ledger_event_id is not distinct from ?
              and task_id is not null
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
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
        scope: TenantScope,
    ): UUID {
        require(scope.admits(tenantId)) { "tenant $tenantId is outside the scoped tenants" }
        val sql =
            """
            insert into octo.reconciliation_break (tenant_id, run_id, kind, source_system, source_ref, ledger_event_id, detail, task_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            returning id
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
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
