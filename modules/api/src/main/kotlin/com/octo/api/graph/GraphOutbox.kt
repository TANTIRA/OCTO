package com.octo.api.graph

import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** One claimed `octo.graph_outbox` row (V45). [payload] is the node state as JSON text. */
data class OutboxRow(
    val seq: Long,
    val tenantId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val payload: String,
    val attempts: Int,
    val claimToken: UUID,
)

/** What the projector reports on: rows still to apply, rows that spent their retry budget, and how stale the oldest pending row is. */
data class OutboxStats(
    val pending: Long,
    val failed: Long,
    val oldestPendingSeconds: Double,
)

/**
 * The projector's side of `octo.graph_outbox`. Platform-wide (`TenantScope.All`) like the other pollers: it drains
 * every tenant's rows and writes each node with its own `tenantId`.
 */
class JdbcGraphOutboxStore(
    private val dataSource: DataSource,
    private val lease: Duration = Duration.ofMinutes(2),
    private val maxAttempts: Int = 8,
    private val maxBackoff: Duration = Duration.ofMinutes(15),
) {
    init {
        require(!lease.isNegative && !lease.isZero) { "graph outbox lease must be positive" }
        require(maxAttempts >= 1) { "graph outbox needs at least one attempt" }
    }

    /**
     * Claims up to [limit] due rows in `seq` order. A row waits while an earlier row of the same aggregate is still
     * pending, so one node's upserts land in the order they were written; `skip locked` keeps two projectors from
     * claiming the same row.
     */
    fun claim(limit: Int): List<OutboxRow> {
        val sql =
            """
            update octo.graph_outbox
            set claim_token = gen_random_uuid(), claimed_until = clock_timestamp() + (? * interval '1 millisecond')
            where seq in (
                select o.seq from octo.graph_outbox o
                where o.status = 'pending' and o.next_attempt_at <= clock_timestamp()
                  and (o.claimed_until is null or o.claimed_until <= clock_timestamp())
                  and not exists (
                      select 1 from octo.graph_outbox p
                      where p.aggregate_id = o.aggregate_id and p.seq < o.seq and p.status = 'pending')
                order by o.seq limit ? for update skip locked)
            returning seq, tenant_id, aggregate_type, aggregate_id, payload::text, attempts, claim_token
            """.trimIndent()
        return dataSource.scoped(TenantScope.All) { connection ->
            connection
                .prepareStatement(sql)
                .use { statement ->
                    statement.setLong(1, lease.toMillis())
                    statement.setInt(2, limit)
                    statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.toRow() else null }.toList() }
                }.sortedBy { it.seq }
        }
    }

    fun applied(row: OutboxRow) =
        update(
            row,
            """update octo.graph_outbox
               set status = 'applied', applied_at = clock_timestamp(), last_error = null, claim_token = null, claimed_until = null
               where seq = ? and claim_token = ?""",
        )

    /** Spends one attempt: backs off exponentially, or marks the row `failed` once [maxAttempts] are used. */
    fun failed(
        row: OutboxRow,
        error: String,
    ) {
        val attempts = row.attempts + 1
        val backoff = Duration.ofSeconds(1L shl minOf(attempts, 20)).coerceAtMost(maxBackoff)
        val sql =
            """
            update octo.graph_outbox
            set attempts = ?, last_error = ?, claim_token = null, claimed_until = null,
                status = case when ? >= ? then 'failed' else 'pending' end,
                next_attempt_at = clock_timestamp() + (? * interval '1 millisecond')
            where seq = ? and claim_token = ?
            """.trimIndent()
        dataSource.scoped(TenantScope.All) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, attempts)
                statement.setString(2, error.take(2000))
                statement.setInt(3, attempts)
                statement.setInt(4, maxAttempts)
                statement.setLong(5, backoff.toMillis())
                statement.setLong(6, row.seq)
                statement.setObject(7, row.claimToken)
                statement.executeUpdate()
            }
        }
    }

    /** Hands a row back unchanged — the graph was unreachable, which is not the row's fault and spends no attempt. */
    fun release(row: OutboxRow) =
        update(row, "update octo.graph_outbox set claim_token = null, claimed_until = null where seq = ? and claim_token = ?")

    fun stats(): OutboxStats =
        dataSource.scoped(TenantScope.All) { connection ->
            connection
                .prepareStatement(
                    """
                    select count(*) filter (where status = 'pending'),
                           count(*) filter (where status = 'failed'),
                           coalesce(extract(epoch from clock_timestamp() - min(created_at) filter (where status = 'pending')), 0)
                    from octo.graph_outbox
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        OutboxStats(rows.getLong(1), rows.getLong(2), rows.getDouble(3))
                    }
                }
        }

    /** Drops applied rows older than [retention]; the graph is rebuilt from source tables, never from the outbox. */
    fun prune(retention: Duration): Int =
        dataSource.scoped(TenantScope.All) { connection ->
            connection
                .prepareStatement(
                    "delete from octo.graph_outbox where status = 'applied' and applied_at < clock_timestamp() - (? * interval '1 millisecond')",
                ).use { statement ->
                    statement.setLong(1, retention.toMillis())
                    statement.executeUpdate()
                }
        }

    /** Runs one of the fixed claim-guarded updates above: only the holder of [row]'s claim token may move it. */
    private fun update(
        row: OutboxRow,
        sql: String,
    ) {
        dataSource.scoped(TenantScope.All) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, row.seq)
                statement.setObject(2, row.claimToken)
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toRow() =
        OutboxRow(
            seq = getLong("seq"),
            tenantId = getObject("tenant_id", UUID::class.java),
            aggregateType = getString("aggregate_type"),
            aggregateId = getObject("aggregate_id", UUID::class.java),
            payload = getString("payload"),
            attempts = getInt("attempts"),
            claimToken = getObject("claim_token", UUID::class.java),
        )
}
