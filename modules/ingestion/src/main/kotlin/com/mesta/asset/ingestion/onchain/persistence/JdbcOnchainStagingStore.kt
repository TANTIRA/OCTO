package com.mesta.asset.ingestion.onchain.persistence

import com.mesta.asset.ingestion.onchain.BalanceSource
import com.mesta.asset.ingestion.onchain.ONCHAIN_SOURCE_SYSTEM
import com.mesta.asset.ingestion.onchain.OnchainBalance
import com.mesta.asset.ingestion.onchain.OnchainStagingStore
import com.mesta.asset.ingestion.onchain.OnchainTransfer
import com.mesta.asset.ingestion.onchain.WatchSource
import java.math.BigDecimal
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of [OnchainStagingStore] against the V10 tables. Thin glue — exercised
 * end-to-end by `OnchainStagingStoreIT` in `:modules:api` and excluded from module coverage the
 * same way `JdbcDecisionStore` is.
 */
class JdbcOnchainStagingStore(
    private val dataSource: DataSource,
) : OnchainStagingStore {
    override fun activeWatchedAddresses(chain: String): List<WatchSource> =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select t.chain, t.address, t.tenant_id, t.label
                      from mesta.tracked_address t
                     where t.chain = ?
                       and (select e.event_type
                              from mesta.tracked_address_event e
                             where e.chain = t.chain and e.address = t.address
                             order by e.seq desc
                             limit 1) = 'watched'
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.executeQuery().use { r ->
                        buildList {
                            while (r.next()) {
                                add(
                                    WatchSource(
                                        chain = r.getString("chain"),
                                        address = r.getString("address"),
                                        tenantId = r.getObject("tenant_id", UUID::class.java),
                                        label = r.getString("label"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override fun newestSignature(
        chain: String,
        wallet: String,
    ): String? =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select signature
                      from mesta.onchain_transfer
                     where chain = ? and wallet = ?
                     order by slot desc
                     limit 1
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
                    s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
                }
        }

    override fun insertTransfers(
        transfers: List<OnchainTransfer>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        if (transfers.isEmpty()) return 0
        val sql =
            """
            insert into mesta.onchain_transfer
                (external_id, chain, signature, slot, block_hash, block_time, commitment,
                 wallet, counterparty, token_account, mint_address, amount_raw, decimals,
                 direction, transfer_kind, helius_payload,
                 source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, 'finalized', ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?, ?)
            on conflict (source_system, external_id) do nothing
            """.trimIndent()
        // TODO(#114): carry the normalized provider payload into helius_payload when the webhook
        // path lands — lineage then covers both delivery routes.
        return dataSource.connection.use { c ->
            c.prepareStatement(sql).use { s ->
                for (t in transfers) {
                    s.setString(1, t.externalId)
                    s.setString(2, t.chain)
                    s.setString(3, t.signature)
                    s.setLong(4, t.slot)
                    s.setString(5, t.blockHash)
                    s.setTimestamp(6, Timestamp.from(t.blockTime))
                    s.setString(7, t.wallet)
                    s.setString(8, t.counterparty)
                    s.setString(9, t.tokenAccount)
                    s.setString(10, t.mintAddress)
                    s.setBigDecimal(11, t.amountRaw.toBigDecimal())
                    s.setInt(12, t.decimals)
                    s.setString(13, t.direction.db)
                    s.setString(14, t.transferKind.db)
                    s.setString(15, ONCHAIN_SOURCE_SYSTEM)
                    s.setString(16, actor)
                    s.setObject(17, ingestionRunId)
                    s.setObject(18, correlationId)
                    s.addBatch()
                }
                s.executeBatch().count { it > 0 }
            }
        }
    }

    override fun insertSnapshots(
        balances: List<OnchainBalance>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        if (balances.isEmpty()) return 0
        val sql =
            """
            insert into mesta.onchain_balance_snapshot
                (external_id, as_of, chain, wallet, token_account, mint_address,
                 amount_raw, decimals, usd_value, source, slot,
                 source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (source_system, external_id) do nothing
            """.trimIndent()
        return dataSource.connection.use { c ->
            c.prepareStatement(sql).use { s ->
                for (b in balances) {
                    s.setString(1, snapshotExternalId(b))
                    s.setTimestamp(2, Timestamp.from(b.asOf))
                    s.setString(3, b.chain)
                    s.setString(4, b.wallet)
                    s.setString(5, b.tokenAccount)
                    s.setString(6, b.mintAddress)
                    s.setBigDecimal(7, b.amountRaw.toBigDecimal())
                    s.setInt(8, b.decimals)
                    s.setBigDecimal(9, b.usdValue?.let(BigDecimal::valueOf))
                    s.setString(10, b.source.db)
                    s.setObject(11, b.slot)
                    s.setString(12, ONCHAIN_SOURCE_SYSTEM)
                    s.setString(13, actor)
                    s.setObject(14, ingestionRunId)
                    s.setObject(15, correlationId)
                    s.addBatch()
                }
                s.executeBatch().count { it > 0 }
            }
        }
    }

    override fun latestSnapshots(
        chain: String,
        wallet: String,
    ): List<OnchainBalance> =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select distinct on (mint_address)
                           wallet, token_account, mint_address, amount_raw, decimals,
                           usd_value, source, slot, as_of
                      from mesta.onchain_balance_snapshot s
                     where chain = ? and wallet = ?
                       and not exists (
                           select 1 from mesta.onchain_balance_snapshot x
                            where x.supersedes_id = s.id)
                     order by mint_address, as_of desc
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
                    s.executeQuery().use { r ->
                        buildList {
                            while (r.next()) {
                                add(
                                    OnchainBalance(
                                        wallet = r.getString("wallet"),
                                        tokenAccount = r.getString("token_account"),
                                        mintAddress = r.getString("mint_address"),
                                        amountRaw = r.getBigDecimal("amount_raw").toBigIntegerExact(),
                                        decimals = r.getInt("decimals"),
                                        usdValue = r.getBigDecimal("usd_value")?.toDouble(),
                                        source = BalanceSource.entries.first { it.db == r.getString("source") },
                                        slot = r.getLong("slot").takeIf { !r.wasNull() },
                                        asOf = r.getObject("as_of", java.time.OffsetDateTime::class.java).toInstant(),
                                        chain = chain,
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    // The observation's identity: an identical report in the same second is the same fact;
    // a different amount or a second source is a different observation worth keeping.
    private fun snapshotExternalId(b: OnchainBalance): String =
        "${b.chain}:${b.wallet}:${b.mintAddress ?: "native"}:balance:${b.source.db}:${b.asOf.epochSecond}:${b.amountRaw}"
}
