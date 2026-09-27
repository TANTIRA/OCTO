package com.octo.dealsourcing.persistence

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.next
import com.octo.dealsourcing.registered
import com.octo.dealsourcing.replay
import com.octo.persistence.TenantScope
import com.octo.persistence.admits
import com.octo.persistence.scoped
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
 * against that replay before inserting it — the `JdbcAccessStore`/`JdbcTaskStore` contract. Writers
 * serialize on a per-prospect advisory lock so neither can interleave an event into a history the
 * other already replayed; readers take no lock — events are append-only and each statement sees a
 * committed snapshot, so a replay can never observe a half-written transition.
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
        require(scope.admits(prospect.tenantId)) { "prospect tenant ${prospect.tenantId} is outside the scoped tenants" }
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
    ): List<UUID> {
        require(prospects.isNotEmpty() && prospects.size <= IMPORT_BATCH_LIMIT) {
            "an import batch holds 1..$IMPORT_BATCH_LIMIT prospects, got ${prospects.size}"
        }
        prospects.forEach { require(scope.admits(it.tenantId)) { "prospect tenant ${it.tenantId} is outside the scoped tenants" } }
        return dataSource.scoped(scope) { connection ->
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
    }

    /**
     * The prospect's state after every stored event, or null when no prospect has that id. No
     * advisory lock: the event log is append-only and the transaction sees committed rows only, so
     * the worst a read can observe is an append that landed between the two selects — a consistent
     * newer state, never a torn one.
     */
    override fun load(
        id: UUID,
        scope: TenantScope,
    ): ProspectState? = dataSource.scoped(scope) { connection -> replayUnlocked(connection, id, scope) }

    /** The raw event rows in append order — the audit trail [load]'s replay summarizes. */
    override fun history(
        id: UUID,
        scope: TenantScope,
    ): List<ProspectEventRow>? =
        dataSource.scoped(scope) { connection ->
            selectProspect(connection, id, scope) ?: return@scoped null
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

    /**
     * Up to [limit] prospects of the tenant currently standing at [stage], newest first. One query
     * reads each prospect's latest event alongside its header — replay under an advisory lock is
     * the `load`/`append` contract; a pipeline page needs the current state, not a lock on every
     * row it lists.
     */
    override fun listAtStage(
        tenantId: UUID,
        stage: ProspectStage,
        limit: Int,
        offset: Int,
        scope: TenantScope,
    ): List<ProspectState> {
        require(limit in 1..PIPELINE_PAGE_LIMIT && offset >= 0) {
            "a pipeline page holds 1..$PIPELINE_PAGE_LIMIT prospects at a non-negative offset, got limit=$limit offset=$offset"
        }
        if (!scope.admits(tenantId)) return emptyList()
        val sql =
            """
            select p.id, p.tenant_id, p.name, p.source, p.sector, p.region, p.description,
                   p.registered_at, p.source_ref,
                   latest.event_type, latest.actor, latest.occurred_at
            from mesta.prospect p
            left join lateral (
                select e.event_type, e.actor, e.occurred_at, e.stage_to
                from mesta.prospect_event e
                where e.prospect_id = p.id
                order by e.seq desc
                limit 1
            ) latest on true
            where p.tenant_id = ?
              and coalesce(latest.stage_to, 'sourced') = ?
            order by p.registered_at desc, p.id
            limit ? offset ?
            """.trimIndent()
        return dataSource.scoped(scope) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenantId)
                statement.setString(2, stage.wireValue)
                statement.setInt(3, limit)
                statement.setInt(4, offset)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.toPipelineState(stage))
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
                replayLocked(connection, prospectId, scope)
                    ?: throw NoSuchElementException("no prospect $prospectId")
            val after = before.next(event)
            insertEvent(connection, prospectId, before.stage, event, provenance)
            after
        }

    private fun replayLocked(
        connection: Connection,
        prospectId: UUID,
        scope: TenantScope,
    ): ProspectState? {
        connection
            .prepareStatement(
                "select pg_advisory_xact_lock(hashtextextended('mesta.prospect:' || ?::text, 0))",
            ).use { statement ->
                statement.setObject(1, prospectId)
                statement.executeQuery().close()
            }
        return replayUnlocked(connection, prospectId, scope)
    }

    private fun replayUnlocked(
        connection: Connection,
        prospectId: UUID,
        scope: TenantScope,
    ): ProspectState? {
        val prospect = selectProspect(connection, prospectId, scope) ?: return null
        return replay(prospect, selectEvents(connection, prospectId))
    }

    private fun selectProspect(
        connection: Connection,
        prospectId: UUID,
        scope: TenantScope,
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
                    ).takeIf { scope.admits(it.tenantId) }
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

    /**
     * One [listAtStage] row: the prospect header plus its newest event's verdict fields. The stage
     * is the one the query filtered for (the latest event's `stage_to`); a terminal event's actor
     * is the decider — replay's `decidedBy`/`lastEventAt` without replaying every event.
     */
    private fun ResultSet.toPipelineState(stage: ProspectStage): ProspectState {
        val eventType = getString(10)
        val occurredAt = getObject(12, OffsetDateTime::class.java)?.toInstant()
        val prospect =
            Prospect(
                id = getObject(1, UUID::class.java),
                tenantId = getObject(2, UUID::class.java),
                name = getString(3),
                source = ProspectSource.fromWireValue(getString(4)),
                sector = getString(5),
                region = getString(6),
                description = getString(7),
                registeredAt = getObject(8, OffsetDateTime::class.java).toInstant(),
                sourceRef = getString(9),
            )
        return ProspectState(
            prospect = prospect,
            stage = stage,
            decidedBy = if (eventType == "passed" || eventType == "invested") getString(11) else null,
            lastEventAt = occurredAt ?: prospect.registeredAt,
        )
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
            "advanced" ->
                ProspectEvent.Advanced(
                    actor,
                    at,
                    stageFrom ?: error("prospect_event 'advanced' row is missing stage_from"),
                    stageTo ?: error("prospect_event 'advanced' row is missing stage_to"),
                    getObject(7, UUID::class.java),
                )
            "passed" ->
                ProspectEvent.Passed(
                    actor,
                    at,
                    stageFrom ?: error("prospect_event 'passed' row is missing stage_from"),
                    getString(5),
                )
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
            statement.setObject(8, (event as? ProspectEvent.Invested)?.taskId ?: (event as? ProspectEvent.Advanced)?.taskId)
            statement.setObject(9, provenance.correlationId)
            statement.executeUpdate()
        }
    }
}
