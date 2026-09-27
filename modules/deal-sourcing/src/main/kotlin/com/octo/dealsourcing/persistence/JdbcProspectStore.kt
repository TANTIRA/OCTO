package com.octo.dealsourcing.persistence

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.TenantScope
import com.octo.dealsourcing.next
import com.octo.dealsourcing.registered
import com.octo.dealsourcing.replay
import com.octo.dealsourcing.scoped
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Where a prospect row or event came from, for the provenance columns V18 requires. */
data class ProspectProvenance(
    val sourceSystem: String,
    val correlationId: UUID,
)

/**
 * JDBC access to `mesta.prospect` and `mesta.prospect_event` (V18). A prospect's stage is never
 * stored: [load] replays its events through the state machine, and [append] validates a new event
 * against that replay before inserting it — the `JdbcAccessStore`/`JdbcTaskStore` contract. Both
 * run under a per-prospect advisory lock, so two writers to one prospect serialize and neither can
 * interleave an event into a history the other already replayed.
 *
 * Every method takes an explicit [TenantScope] (#197): request-path calls carry the caller's
 * `User` scope and platform scans carry `All`; nothing touches tenant rows unscoped.
 */
class JdbcProspectStore(
    private val dataSource: DataSource,
) : ProspectStore {
    /** Registers [prospect]; the row itself is the registration fact (its `registered_at`). */
    override fun create(
        prospect: Prospect,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ) {
        val prospectSql =
            """
            insert into mesta.prospect (id, tenant_id, name, source, sector, region, description,
                                        registered_at, source_ref, source_system, actor, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        dataSource.scoped(scope) { connection ->
            connection.prepareStatement(prospectSql).use { statement ->
                statement.setObject(1, prospect.id)
                statement.setObject(2, prospect.tenantId)
                statement.setString(3, prospect.name)
                statement.setString(4, prospect.source.wireValue)
                statement.setString(5, prospect.sector)
                statement.setString(6, prospect.region)
                statement.setString(7, prospect.description)
                statement.setObject(8, prospect.registeredAt.atOffset(ZoneOffset.UTC))
                statement.setString(9, prospect.sourceRef)
                statement.setString(10, provenance.sourceSystem)
                statement.setString(11, actor)
                statement.setObject(12, provenance.correlationId)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Registers every prospect that isn't a duplicate of an existing `(tenant, source, source_ref)`
     * row — the CRM-adapter contract: a re-sync is a no-op, not a second prospect. Returns the ids
     * actually inserted; a `null` `source_ref` never dedupes (the V21 index is partial).
     */
    override fun importBatch(
        prospects: List<Prospect>,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ): List<UUID> =
        dataSource.scoped(scope) { connection ->
            val sql =
                """
                insert into mesta.prospect (id, tenant_id, name, source, sector, region, description,
                                            registered_at, source_ref, source_system, actor, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, source, source_ref) where source_ref is not null do nothing
                returning id
                """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                buildList {
                    for (prospect in prospects) {
                        statement.setObject(1, prospect.id)
                        statement.setObject(2, prospect.tenantId)
                        statement.setString(3, prospect.name)
                        statement.setString(4, prospect.source.wireValue)
                        statement.setString(5, prospect.sector)
                        statement.setString(6, prospect.region)
                        statement.setString(7, prospect.description)
                        statement.setObject(8, prospect.registeredAt.atOffset(ZoneOffset.UTC))
                        statement.setString(9, prospect.sourceRef)
                        statement.setString(10, provenance.sourceSystem)
                        statement.setString(11, actor)
                        statement.setObject(12, provenance.correlationId)
                        statement.executeQuery().use { rows ->
                            if (rows.next()) add(rows.getObject(1, UUID::class.java))
                        }
                    }
                }
            }
        }

    /** The prospect's state after every stored event, or null when no prospect has that id. */
    override fun load(
        id: UUID,
        scope: TenantScope,
    ): ProspectState? = dataSource.scoped(scope) { connection -> replayLocked(connection, id) }

    /** The raw event rows in append order — the audit trail [load]'s replay summarizes. */
    override fun history(
        id: UUID,
        scope: TenantScope,
    ): List<ProspectEventRow>? =
        dataSource.scoped(scope) { connection ->
            selectProspect(connection, id) ?: return@scoped null
            connection
                .prepareStatement(
                    """
                    select seq, event_type, stage_from, stage_to, actor, rationale,
                           occurred_at, recorded_at, correlation_id, task_id
                    from mesta.prospect_event
                    where prospect_id = ?
                    order by seq
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.toEventRow())
                            }
                        }
                    }
                }
        }

    /** Every prospect of the tenant currently standing at [stage]. */
    override fun listAtStage(
        tenantId: UUID,
        stage: ProspectStage,
        scope: TenantScope,
    ): List<ProspectState> {
        val sql =
            """
            select p.id
            from mesta.prospect p
            left join lateral (
                select e.event_type, e.stage_to
                from mesta.prospect_event e
                where e.prospect_id = p.id
                order by e.seq desc
                limit 1
            ) latest on true
            where p.tenant_id = ?
              and coalesce(latest.stage_to, 'sourced') = ?
            order by p.registered_at desc
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, stage.wireValue)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(replayLocked(connection, rows.getObject(1, UUID::class.java))!!)
                        }
                    }
                }
            }
        }
    }

    /**
     * Validates [event] against the prospect's replayed state and stores it, in one transaction.
     * Throws [IllegalArgumentException] for a transition the state machine rejects, or
     * [NoSuchElementException] for an unknown prospect; nothing is written in either case.
     */
    override fun append(
        prospectId: UUID,
        event: ProspectEvent,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ): ProspectState =
        dataSource.scoped(scope) { connection ->
            val before =
                replayLocked(connection, prospectId)
                    ?: throw NoSuchElementException("no prospect $prospectId")
            val after = before.next(event)
            insertEvent(connection, prospectId, before.stage, event, provenance)
            after
        }

    private fun replayLocked(
        connection: Connection,
        prospectId: UUID,
    ): ProspectState? {
        connection
            .prepareStatement(
                "select pg_advisory_xact_lock(hashtextextended('mesta.prospect:' || ?::text, 0))",
            ).use { statement ->
                statement.setObject(1, prospectId)
                statement.executeQuery().close()
            }
        val prospect = selectProspect(connection, prospectId) ?: return null
        return replay(prospect, selectEvents(connection, prospectId))
    }

    private fun selectProspect(
        connection: Connection,
        prospectId: UUID,
    ): Prospect? =
        connection
            .prepareStatement(
                "select tenant_id, name, source, sector, region, description, registered_at, source_ref from mesta.prospect where id = ?",
            ).use { statement ->
                statement.setObject(1, prospectId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    Prospect(
                        id = prospectId,
                        tenantId = rows.getObject(1, UUID::class.java),
                        name = rows.getString(2),
                        source = ProspectSource.fromWireValue(rows.getString(3)),
                        sector = rows.getString(4),
                        region = rows.getString(5),
                        description = rows.getString(6),
                        registeredAt = rows.getObject(7, OffsetDateTime::class.java).toInstant(),
                        sourceRef = rows.getString(8),
                    )
                }
            }

    /** Events in append order: V18's seq, because occurred_at can tie. */
    private fun selectEvents(
        connection: Connection,
        prospectId: UUID,
    ): List<ProspectEvent> =
        connection
            .prepareStatement(
                """
                select event_type, stage_from, stage_to, actor, rationale, occurred_at, task_id
                from mesta.prospect_event
                where prospect_id = ?
                order by seq
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, prospectId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.toEvent())
                        }
                    }
                }
            }

    private fun ResultSet.toEventRow() =
        ProspectEventRow(
            seq = getLong(1),
            eventType = getString(2),
            stageFrom = getString(3)?.let(ProspectStage::fromWireValue),
            stageTo = getString(4)?.let(ProspectStage::fromWireValue),
            actor = getString(5),
            rationale = getString(6),
            occurredAt = getObject(7, OffsetDateTime::class.java).toInstant(),
            recordedAt = getObject(8, OffsetDateTime::class.java).toInstant(),
            correlationId = getObject(9, UUID::class.java),
            taskId = getObject(10, UUID::class.java),
        )

    private fun ResultSet.toEvent(): ProspectEvent {
        val actor = getString(4)
        val at = getObject(6, OffsetDateTime::class.java).toInstant()
        val stageFrom = getString(2)?.let(ProspectStage::fromWireValue)
        val stageTo = getString(3)?.let(ProspectStage::fromWireValue)
        return when (val type = getString(1)) {
            "advanced" -> ProspectEvent.Advanced(actor, at, stageFrom!!, stageTo!!)
            "passed" -> ProspectEvent.Passed(actor, at, stageFrom!!, getString(5))
            "invested" -> ProspectEvent.Invested(actor, at, getString(5), getObject(7, UUID::class.java))
            else -> error("unknown prospect event type $type")
        }
    }

    /** [stageFrom] is the replayed stage before the event — the row stays self-describing. */
    private fun insertEvent(
        connection: Connection,
        prospectId: UUID,
        stageFrom: ProspectStage,
        event: ProspectEvent,
        provenance: ProspectProvenance,
    ) {
        val (type, stageTo, rationale) =
            when (event) {
                is ProspectEvent.Advanced -> Triple("advanced", event.to.wireValue, null)
                is ProspectEvent.Passed -> Triple("passed", "passed", event.rationale)
                is ProspectEvent.Invested -> Triple("invested", "invested", event.rationale)
            }
        val sql =
            """
            insert into mesta.prospect_event (prospect_id, event_type, stage_from, stage_to, actor, rationale, occurred_at, task_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, prospectId)
            statement.setString(2, type)
            statement.setString(3, stageFrom.wireValue)
            statement.setString(4, stageTo)
            statement.setString(5, event.actor)
            statement.setString(6, rationale)
            statement.setObject(7, event.at.atOffset(ZoneOffset.UTC))
            statement.setObject(8, (event as? ProspectEvent.Invested)?.taskId)
            statement.setObject(9, provenance.correlationId)
            statement.executeUpdate()
        }
    }
}
