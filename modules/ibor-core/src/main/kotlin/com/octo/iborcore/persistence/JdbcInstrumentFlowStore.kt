package com.octo.iborcore.persistence

import com.octo.iborcore.InstrumentFlow
import com.octo.iborcore.InstrumentFlowStore
import com.octo.iborcore.InstrumentFlowType
import com.octo.iborcore.InstrumentKey
import com.octo.iborcore.PROMOTION_ACTOR
import com.octo.iborcore.ProjectedInstrument
import com.octo.iborcore.StagedTransfer
import com.octo.iborcore.instrumentFlowProjection
import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of [InstrumentFlowStore] against the V10 tables. Thin glue — exercised
 * end-to-end by `OnchainPromotionIT` in `:modules:api` and excluded from module coverage the
 * same way `JdbcIborReader` is.
 *
 * Never a bare `dataSource.connection` (#310, same failure as #309): `onchain_transfer` and
 * `instrument_flow` derive their tenant through V30's `tracked_address` subselect, and with the
 * `app.tenant_ids` GUC unset every policy denies — promotion would read nothing and every insert
 * would 42501 under the runtime role. The promotion methods run under [TenantScope.All] exactly
 * like `JdbcOnchainStagingStore`: promotion is a platform pass over every tenant's staged facts,
 * and each flow's tenant is still derived from its wallet's `tracked_address` row, so a promoted
 * flow is visible only to the tenant tracking that wallet. [flowsFor] is the
 * derivation read, so it runs under the caller's scope, like `JdbcIborReader`.
 */
class JdbcInstrumentFlowStore(
    private val dataSource: DataSource,
) : InstrumentFlowStore {
    override fun unpromotedTransfers(): List<StagedTransfer> =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select s.id, s.external_id, s.chain, s.signature, s.slot, s.block_time,
                           s.wallet, s.token_account, s.mint_address, s.amount_raw, s.decimals,
                           s.transfer_kind, s.supersedes_id, s.rationale, s.recorded_at,
                       s.source_system,
                           s.ingestion_run_id, s.correlation_id
                      from octo.onchain_transfer s
                     where s.commitment = 'finalized'
                       and not exists (
                           select 1 from octo.instrument_flow f
                            where f.source_system = s.source_system
                              and f.external_id = s.external_id)
                     order by s.recorded_at, s.id
                    """.trimIndent(),
                ).use { s ->
                    s.executeQuery().use { r ->
                        buildList {
                            while (r.next()) {
                                add(
                                    StagedTransfer(
                                        id = r.uuid("id")!!,
                                        externalId = r.getString("external_id"),
                                        chain = r.getString("chain"),
                                        signature = r.getString("signature"),
                                        slot = r.getLong("slot"),
                                        blockTime = r.instant("block_time"),
                                        wallet = r.getString("wallet"),
                                        tokenAccount = r.getString("token_account"),
                                        mintAddress = r.getString("mint_address"),
                                        amountRaw = r.getBigDecimal("amount_raw").toBigIntegerExact(),
                                        decimals = r.getInt("decimals"),
                                        transferKind = r.getString("transfer_kind"),
                                        supersedesId = r.uuid("supersedes_id"),
                                        rationale = r.getString("rationale"),
                                        recordedAt = r.instant("recorded_at"),
                                        sourceSystem = r.getString("source_system"),
                                        ingestionRunId = r.uuid("ingestion_run_id")!!,
                                        correlationId = r.uuid("correlation_id")!!,
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override fun instrumentIds(): Map<InstrumentKey, UUID> =
        dataSource.scoped(TenantScope.All) { c ->
            c.prepareStatement("select id, chain, mint_address from octo.instrument").use { s ->
                s.executeQuery().use { r ->
                    buildMap {
                        while (r.next()) {
                            put(InstrumentKey(r.getString("chain"), r.getString("mint_address")), r.uuid("id")!!)
                        }
                    }
                }
            }
        }

    override fun flowIdForStaging(stagingRowId: UUID): UUID? =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select f.id
                      from octo.instrument_flow f
                      join octo.onchain_transfer o
                        on o.source_system = f.source_system
                       and o.external_id = f.external_id
                     where o.id = ?
                    """.trimIndent(),
                ).use { s ->
                    s.setObject(1, stagingRowId)
                    s.executeQuery().use { r -> if (r.next()) r.uuid("id") else null }
                }
        }

    override fun insertFlow(
        flow: InstrumentFlow,
        sourceSystem: String,
        ingestionRunId: UUID,
        correlationId: UUID,
    ): Boolean =
        dataSource.scoped(TenantScope.All) { c ->
            val inserted =
                c
                    .prepareStatement(
                        """
                        insert into octo.instrument_flow
                            (id, external_id, instrument_id, chain, wallet, token_account, flow_type,
                             amount_raw, decimals, occurred_at, recorded_at, slot, signature,
                             supersedes_id, rationale, source_system, actor, ingestion_run_id, correlation_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (source_system, external_id) do nothing
                        """.trimIndent(),
                    ).use { s ->
                        s.setObject(1, flow.id)
                        s.setString(2, flow.externalId)
                        s.setObject(3, flow.instrumentId)
                        s.setString(4, flow.chain)
                        s.setString(5, flow.wallet)
                        s.setString(6, flow.tokenAccount)
                        s.setString(7, flow.flowType.wireValue)
                        s.setBigDecimal(8, flow.amountRaw.toBigDecimal())
                        s.setInt(9, flow.decimals)
                        s.setTimestamp(10, Timestamp.from(flow.occurredAt))
                        s.setTimestamp(11, Timestamp.from(flow.recordedAt))
                        s.setObject(12, flow.slot)
                        s.setString(13, flow.signature)
                        s.setObject(14, flow.supersedesId)
                        s.setString(15, flow.rationale)
                        s.setString(16, sourceSystem)
                        s.setString(17, PROMOTION_ACTOR)
                        s.setObject(18, ingestionRunId)
                        s.setObject(19, correlationId)
                        s.executeUpdate() == 1
                    }
            if (inserted) project(c, flow)
            inserted
        }

    /**
     * Enqueues the flow's graph upserts on [connection], after the ledger row is inserted, so both commit together.
     * A wallet with no tenant (untracked, or a platform watch) stays in the ledger and is not projected: the outbox
     * requires a tenant and the graph keys wallets per tenant.
     */
    private fun project(
        connection: Connection,
        flow: InstrumentFlow,
    ) {
        val tenantId =
            connection.prepareStatement("select tenant_id from octo.tracked_address where chain = ? and address = ?").use { s ->
                s.setString(1, flow.chain)
                s.setString(2, flow.wallet)
                s.executeQuery().use { r -> if (r.next()) r.getObject("tenant_id", UUID::class.java) else null }
            } ?: return
        val instrument =
            connection
                .prepareStatement(
                    "select external_key, chain, mint_address, instrument_kind, decimals from octo.instrument where id = ?",
                ).use { s ->
                    s.setObject(1, flow.instrumentId)
                    s.executeQuery().use { r ->
                        check(r.next()) { "instrument ${flow.instrumentId} disappeared during promotion" }
                        ProjectedInstrument(
                            id = flow.instrumentId,
                            externalKey = r.getString("external_key"),
                            chain = r.getString("chain"),
                            mintAddress = r.getString("mint_address"),
                            kind = r.getString("instrument_kind"),
                            decimals = r.getInt("decimals"),
                        )
                    }
                }
        for (spec in instrumentFlowProjection(tenantId, flow, lineageRoot(connection, flow), instrument)) {
            enqueueGraphUpsert(connection, tenantId, spec.aggregateType, spec.aggregateId, spec.payload())
        }
    }

    /** The first row of [flow]'s lineage — the graph node's octoId, stable across every correction. */
    private fun lineageRoot(
        connection: Connection,
        flow: InstrumentFlow,
    ): UUID {
        if (flow.supersedesId == null) return flow.id
        val sql =
            """
            with recursive lineage as (
                select id, supersedes_id from octo.instrument_flow where id = ?
                union all
                select f.id, f.supersedes_id from octo.instrument_flow f join lineage l on f.id = l.supersedes_id)
            select id from lineage where supersedes_id is null
            """.trimIndent()
        return connection.prepareStatement(sql).use { s ->
            s.setObject(1, flow.id)
            s.executeQuery().use { r ->
                check(r.next()) { "flow ${flow.id} supersedes ${flow.supersedesId}, whose lineage has no root" }
                r.getObject("id", UUID::class.java)
            }
        }
    }

    override fun flowsFor(
        chain: String,
        wallet: String,
        scope: TenantScope,
    ): List<InstrumentFlow> =
        dataSource.scoped(scope) { c ->
            c
                .prepareStatement(
                    """
                    select id, external_id, instrument_id, wallet, token_account, flow_type,
                           amount_raw, decimals, occurred_at, recorded_at, slot, signature,
                           supersedes_id, rationale
                      from octo.instrument_flow
                     where chain = ? and wallet = ?
                     order by recorded_at, id
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
                    s.executeQuery().use { r ->
                        buildList {
                            while (r.next()) {
                                add(
                                    InstrumentFlow(
                                        id = r.uuid("id")!!,
                                        externalId = r.getString("external_id"),
                                        instrumentId = r.uuid("instrument_id")!!,
                                        chain = chain,
                                        wallet = r.getString("wallet"),
                                        tokenAccount = r.getString("token_account"),
                                        flowType = InstrumentFlowType.entries.first { it.wireValue == r.getString("flow_type") },
                                        amountRaw = r.getBigDecimal("amount_raw").toBigIntegerExact(),
                                        decimals = r.getInt("decimals"),
                                        occurredAt = r.instant("occurred_at"),
                                        recordedAt = r.instant("recorded_at"),
                                        slot = r.getLong("slot").takeIf { !r.wasNull() },
                                        signature = r.getString("signature"),
                                        supersedesId = r.uuid("supersedes_id"),
                                        rationale = r.getString("rationale"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

    private fun ResultSet.instant(column: String) = getObject(column, OffsetDateTime::class.java).toInstant()
}
