package com.mesta.asset.iborcore.persistence

import com.mesta.asset.iborcore.InstrumentFlow
import com.mesta.asset.iborcore.InstrumentFlowStore
import com.mesta.asset.iborcore.InstrumentFlowType
import com.mesta.asset.iborcore.InstrumentKey
import com.mesta.asset.iborcore.PROMOTION_ACTOR
import com.mesta.asset.iborcore.StagedTransfer
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of [InstrumentFlowStore] against the V10 tables. Thin glue — exercised
 * end-to-end by `OnchainPromotionIT` in `:modules:api` and excluded from module coverage the
 * same way `JdbcIborReader` is.
 */
class JdbcInstrumentFlowStore(
    private val dataSource: DataSource,
) : InstrumentFlowStore {
    override fun unpromotedTransfers(): List<StagedTransfer> =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select s.id, s.external_id, s.chain, s.signature, s.slot, s.block_time,
                           s.wallet, s.token_account, s.mint_address, s.amount_raw, s.decimals,
                           s.transfer_kind, s.supersedes_id, s.rationale, s.recorded_at,
                       s.source_system,
                           s.ingestion_run_id, s.correlation_id
                      from mesta.onchain_transfer s
                     where s.commitment = 'finalized'
                       and not exists (
                           select 1 from mesta.instrument_flow f
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
        dataSource.connection.use { c ->
            c.prepareStatement("select id, chain, mint_address from mesta.instrument").use { s ->
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
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select f.id
                      from mesta.instrument_flow f
                      join mesta.onchain_transfer o
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
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    insert into mesta.instrument_flow
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
        }

    override fun flowsFor(
        chain: String,
        wallet: String,
    ): List<InstrumentFlow> =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select id, external_id, instrument_id, wallet, token_account, flow_type,
                           amount_raw, decimals, occurred_at, recorded_at, slot, signature,
                           supersedes_id, rationale
                      from mesta.instrument_flow
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
