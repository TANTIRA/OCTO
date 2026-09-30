package com.octo.api.access.persistence

import com.octo.api.access.MembershipEvent
import com.octo.api.access.MembershipState
import com.octo.api.access.Tenant
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.access.next
import com.octo.api.access.registered
import com.octo.api.access.replay
import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Where a tenant or membership row came from, for the provenance columns V8 requires. */
data class AccessProvenance(
    val sourceSystem: String,
    val correlationId: UUID,
)

/**
 * JDBC access to `octo.tenant`, `octo.tenant_member` and `octo.tenant_member_event` (V8). Access
 * state is never stored: [load] replays a member's events through the state machine, and [append]
 * validates a new event against that replay before inserting it. Both run under the per-member
 * advisory lock the V8 trigger takes, so two writers to one membership serialize and neither can
 * interleave an event into a history the other already replayed.
 */
class JdbcAccessStore(
    private val dataSource: DataSource,
) : TenantDirectory,
    TenantPlacements,
    AccessAdministration {
    fun createTenant(
        tenant: Tenant,
        provenance: AccessProvenance,
    ) {
        // The access store is the platform's security substrate (#197): it reads and writes the
        // membership tables the RLS policies themselves consult, so it runs under an explicit
        // `All` scope — the queries' own predicates still constrain the rows.
        dataSource.scoped(TenantScope.All) { connection -> insertTenant(connection, tenant, provenance) }
    }

    /** Registers [userId] in the tenant at [registeredAt]. Access comes from events, not this row. */
    fun registerMember(
        tenantId: UUID,
        userId: UUID,
        registeredAt: Instant,
        provenance: AccessProvenance,
    ) {
        dataSource.scoped(TenantScope.All) { connection -> insertMember(connection, tenantId, userId, registeredAt, provenance) }
    }

    /**
     * Tenant + first admin as one atomic provision (access boundary): the tenant row, the member
     * registration and the granting event share one transaction, so a failed grant can never leave
     * an admin-less tenant behind.
     */
    override fun provisionTenant(
        tenant: Tenant,
        adminUserId: UUID,
        grantor: String,
        registeredAt: Instant,
        provenance: AccessProvenance,
    ): MembershipState =
        dataSource.scoped(TenantScope.All) { connection ->
            insertTenant(connection, tenant, provenance)
            insertMember(connection, tenant.id, adminUserId, registeredAt, provenance)
            val event = MembershipEvent.Granted(grantor, registeredAt, TenantRole.ADMIN)
            val after = registered(tenant.id, adminUserId, registeredAt).next(event)
            insertEvent(connection, tenant.id, adminUserId, event, provenance)
            after
        }

    /** The membership's state after every stored event, or null when the pair is not registered. */
    fun load(
        tenantId: UUID,
        userId: UUID,
    ): MembershipState? = dataSource.scoped(TenantScope.All) { connection -> replayLocked(connection, tenantId, userId) }

    /**
     * Validates [event] against the membership's current state and stores it, in one transaction.
     * Throws [IllegalArgumentException] for a transition the state machine rejects, or
     * [NoSuchElementException] for an unregistered pair; nothing is written in either case.
     */
    override fun append(
        tenantId: UUID,
        userId: UUID,
        event: MembershipEvent,
        provenance: AccessProvenance,
    ): MembershipState =
        dataSource.scoped(TenantScope.All) { connection ->
            val before =
                replayLocked(connection, tenantId, userId)
                    ?: throw NoSuchElementException("no member $userId in tenant $tenantId")
            val after = before.next(event)
            insertEvent(connection, tenantId, userId, event, provenance)
            after
        }

    /**
     * [append] for a grant that may be the pair's first: registers [userId] at [event]'s time when
     * absent, in the same transaction and under the same lock, so a grant the state machine refuses
     * leaves no registration behind — the row is append-only and could never be removed.
     */
    override fun grant(
        tenantId: UUID,
        userId: UUID,
        event: MembershipEvent.Granted,
        provenance: AccessProvenance,
    ): MembershipState =
        dataSource.scoped(TenantScope.All) { connection ->
            val existing = replayLocked(connection, tenantId, userId)
            val after = (existing ?: registered(tenantId, userId, event.at)).next(event)
            if (existing == null) insertMember(connection, tenantId, userId, event.at, provenance)
            insertEvent(connection, tenantId, userId, event, provenance)
            after
        }

    /**
     * The tenants [userId] currently holds a role in. The state machine guarantees the latest event
     * fully determines access — revoked events carry a null role — so reading the latest event per
     * member derives the same state a full replay does, without loading the history.
     */
    override fun tenantsOf(userId: UUID): List<TenantAccess> {
        val sql =
            """
            select m.tenant_id, t.slug, latest.role
            from octo.tenant_member m
            join octo.tenant t on t.id = m.tenant_id
            join lateral (
                select e.role
                from octo.tenant_member_event e
                where e.tenant_id = m.tenant_id and e.user_id = m.user_id
                order by e.seq desc
                limit 1
            ) latest on true
            where m.user_id = ? and latest.role is not null
            order by t.slug
            """.trimIndent()
        return dataSource.scoped(TenantScope.All) { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                TenantAccess(
                                    tenantId = rows.getObject(1, UUID::class.java),
                                    slug = rows.getString(2),
                                    role = TenantRole.fromWireValue(rows.getString(3)),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** The tenant's placement (V32): `pool` + null key until promoted per the isolation runbook. */
    override fun placementOf(tenantId: UUID): TenantPlacement? =
        dataSource.scoped(TenantScope.All) { connection ->
            connection
                .prepareStatement(
                    "select isolation_tier, datasource_key from octo.tenant where id = ?",
                ).use { statement ->
                    statement.setObject(1, tenantId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) {
                            null
                        } else {
                            TenantPlacement(tenantId, rows.getString(1), rows.getString(2))
                        }
                    }
                }
        }

    private fun replayLocked(
        connection: Connection,
        tenantId: UUID,
        userId: UUID,
    ): MembershipState? {
        connection
            .prepareStatement(
                "select pg_advisory_xact_lock(hashtextextended('octo.tenant_member:' || ?::text || ':' || ?::text, 0))",
            ).use { statement ->
                statement.setObject(1, tenantId)
                statement.setObject(2, userId)
                statement.executeQuery().close()
            }
        val registeredAt = selectMember(connection, tenantId, userId) ?: return null
        return replay(registered(tenantId, userId, registeredAt), selectEvents(connection, tenantId, userId))
    }

    private fun selectMember(
        connection: Connection,
        tenantId: UUID,
        userId: UUID,
    ): Instant? =
        connection
            .prepareStatement(
                "select created_at from octo.tenant_member where tenant_id = ? and user_id = ?",
            ).use { statement ->
                statement.setObject(1, tenantId)
                statement.setObject(2, userId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    rows.getObject(1, OffsetDateTime::class.java).toInstant()
                }
            }

    /** Events in append order: V8's seq, because occurred_at can tie. */
    private fun selectEvents(
        connection: Connection,
        tenantId: UUID,
        userId: UUID,
    ): List<MembershipEvent> =
        connection
            .prepareStatement(
                """
                select event_type, role, actor, rationale, occurred_at
                from octo.tenant_member_event
                where tenant_id = ? and user_id = ?
                order by seq
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, tenantId)
                statement.setObject(2, userId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val actor = rows.getString(3)
                            val at = rows.getObject(5, OffsetDateTime::class.java).toInstant()
                            add(
                                when (val type = rows.getString(1)) {
                                    "granted" -> MembershipEvent.Granted(actor, at, role(rows))
                                    "role-changed" -> MembershipEvent.RoleChanged(actor, at, role(rows))
                                    "revoked" -> MembershipEvent.Revoked(actor, at, rows.getString(4))
                                    else -> error("unknown membership event type $type")
                                },
                            )
                        }
                    }
                }
            }

    private fun role(rows: ResultSet): TenantRole = TenantRole.fromWireValue(rows.getString(2))

    private fun insertTenant(
        connection: Connection,
        tenant: Tenant,
        provenance: AccessProvenance,
    ) {
        connection
            .prepareStatement(
                "insert into octo.tenant (id, slug, display_name, source_system, correlation_id) values (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, tenant.id)
                statement.setString(2, tenant.slug)
                statement.setString(3, tenant.displayName)
                statement.setString(4, provenance.sourceSystem)
                statement.setObject(5, provenance.correlationId)
                statement.executeUpdate()
            }
    }

    private fun insertMember(
        connection: Connection,
        tenantId: UUID,
        userId: UUID,
        registeredAt: Instant,
        provenance: AccessProvenance,
    ) {
        connection
            .prepareStatement(
                "insert into octo.tenant_member (tenant_id, user_id, created_at, source_system, correlation_id) " +
                    "values (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, tenantId)
                statement.setObject(2, userId)
                statement.setObject(3, registeredAt.atOffset(ZoneOffset.UTC))
                statement.setString(4, provenance.sourceSystem)
                statement.setObject(5, provenance.correlationId)
                statement.executeUpdate()
            }
    }

    private fun insertEvent(
        connection: Connection,
        tenantId: UUID,
        userId: UUID,
        event: MembershipEvent,
        provenance: AccessProvenance,
    ) {
        val (type, role, rationale) =
            when (event) {
                is MembershipEvent.Granted -> Triple("granted", event.role.wireValue, null)
                is MembershipEvent.RoleChanged -> Triple("role-changed", event.role.wireValue, null)
                is MembershipEvent.Revoked -> Triple("revoked", null, event.rationale)
            }
        val sql =
            """
            insert into octo.tenant_member_event (tenant_id, user_id, event_type, role, actor, rationale, occurred_at, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, tenantId)
            statement.setObject(2, userId)
            statement.setString(3, type)
            statement.setString(4, role)
            statement.setString(5, event.actor)
            statement.setString(6, rationale)
            statement.setObject(7, event.at.atOffset(ZoneOffset.UTC))
            statement.setObject(8, provenance.correlationId)
            statement.executeUpdate()
        }
    }
}
