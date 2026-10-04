package com.octo.ingestion.onchain.persistence

import com.octo.ingestion.onchain.BalanceSource
import com.octo.ingestion.onchain.OnchainBalance
import com.octo.ingestion.onchain.OnchainEvidence
import com.octo.ingestion.onchain.OnchainStagingStore
import com.octo.ingestion.onchain.OnchainTransfer
import com.octo.ingestion.onchain.StagedNativeLeg
import com.octo.ingestion.onchain.SolanaHistoryCursor
import com.octo.ingestion.onchain.TokenContract
import com.octo.ingestion.onchain.TransferDirection
import com.octo.ingestion.onchain.TransferKind
import com.octo.ingestion.onchain.WatchSource
import com.octo.ingestion.onchain.isSolanaNetworkFee
import com.octo.ingestion.onchain.legacyBalanceExternalId
import com.octo.ingestion.onchain.omitSolanaFeesAlreadyBooked
import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of [OnchainStagingStore] against the V10 tables. Thin glue — exercised
 * end-to-end by `OnchainStagingStoreIT` in `:modules:api` and excluded from module coverage the
 * same way `JdbcDecisionStore` is.
 *
 * Every method runs under `dataSource.scoped(TenantScope.All)`, never a bare `dataSource.connection`
 * (#309). `onchain_transfer`/`onchain_balance_snapshot`/`onchain_claim_evidence` derive their tenant
 * via a V30 subselect against `tracked_address` rather than a stamped column, but the RLS predicate
 * still reads the transaction-local `app.tenant_ids` GUC — unset (a bare `.connection`) evaluates
 * every predicate to `deny`, not `defer to the subselect`. `All` is the correct scope, not a missing
 * one: this store is the ingestion collectors'/webhook's shared staging surface and legitimately
 * spans every tenant's tracked addresses in one poll/delivery, exactly like `activeWatchedAddresses`
 * below already did.
 */
class JdbcOnchainStagingStore(
    private val dataSource: DataSource,
) : OnchainStagingStore {
    // Platform watch discovery legitimately spans tenants (#197): `All` is the explicit
    // contract, not a missing scope — the staging tables this feeds carry no tenant_id.
    override fun activeWatchedAddresses(chain: String): List<WatchSource> =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select t.chain, t.address, t.tenant_id, t.label
                      from octo.tracked_address t
                     where t.chain = ?
                       and (select e.event_type
                              from octo.tracked_address_event e
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

    /**
     * The newest staged slot for a chain/wallet, or null when nothing is staged.
     *
     * Staking rewards are excluded: they are staged at the reward's `effectiveSlot` by the staking
     * collector, not by the transaction scan, so counting them would move the poller's `slot.gt`
     * cursor past history it has not scanned yet.
     *
     * Exactly one query, and the `use` block's value is the return. A second `executeQuery()` here is
     * not a "missing result-set read" — the block above already is the result. `JdbcOnchainStagingStoreQueryCountTest`
     * fails if the query count moves off 1.
     */
    override fun newestSlot(
        chain: String,
        wallet: String,
    ): Long? =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select max(slot)
                      from octo.onchain_transfer
                     where chain = ? and wallet = ? and transfer_kind <> ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
                    s.setString(3, TransferKind.STAKING_REWARD.db)
                    s.executeQuery().use { r ->
                        if (r.next()) {
                            val slot = r.getLong(1)
                            if (r.wasNull()) null else slot
                        } else {
                            null
                        }
                    }
                }
        }

    override fun historyCursor(
        chain: String,
        wallet: String,
    ): SolanaHistoryCursor? =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select floor_slot, resume_token, pending_tip_slot
                      from octo.solana_history_cursor
                     where chain = ? and address = ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
                    s.executeQuery().use { r ->
                        if (!r.next()) {
                            null
                        } else {
                            SolanaHistoryCursor(
                                floorSlot = r.getLong("floor_slot").takeIf { !r.wasNull() },
                                resumeToken = r.getString("resume_token"),
                                pendingTipSlot = r.getLong("pending_tip_slot").takeIf { !r.wasNull() },
                            )
                        }
                    }
                }
        }

    override fun saveHistoryCursor(
        chain: String,
        wallet: String,
        cursor: SolanaHistoryCursor,
    ) {
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    insert into octo.solana_history_cursor
                        (chain, address, floor_slot, resume_token, pending_tip_slot, updated_at)
                    values (?, ?, ?, ?, ?, now())
                    on conflict (chain, address) do update
                        set floor_slot = excluded.floor_slot,
                            resume_token = excluded.resume_token,
                            pending_tip_slot = excluded.pending_tip_slot,
                            updated_at = now()
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
                    if (cursor.floorSlot == null) s.setNull(3, Types.BIGINT) else s.setLong(3, cursor.floorSlot)
                    s.setString(4, cursor.resumeToken)
                    if (cursor.pendingTipSlot == null) s.setNull(5, Types.BIGINT) else s.setLong(5, cursor.pendingTipSlot)
                    s.executeUpdate()
                }
        }
    }

    override fun newestStagedSlot(chain: String): Long? =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select max(slot)
                      from octo.onchain_transfer
                     where chain = ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.executeQuery().use { r -> if (r.next()) r.getLong(1).takeIf { !r.wasNull() } else null }
                }
        }

    override fun scannedThrough(chain: String): Long? =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select scanned_through
                      from octo.evm_scan_checkpoint
                     where chain = ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.executeQuery().use { r -> if (r.next()) r.getLong(1).takeIf { !r.wasNull() } else null }
                }
        }

    override fun recordScannedThrough(
        chain: String,
        block: Long,
    ) {
        require(chain.isNotBlank()) { "chain required" }
        require(block >= 0) { "scanned-through block must be >= 0" }
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    insert into octo.evm_scan_checkpoint (chain, scanned_through)
                    values (?, ?)
                    on conflict (chain) do update
                       set scanned_through = greatest(octo.evm_scan_checkpoint.scanned_through, excluded.scanned_through),
                           updated_at = now()
                     where excluded.scanned_through > octo.evm_scan_checkpoint.scanned_through
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setLong(2, block)
                    s.executeUpdate()
                }
        }
    }

    override fun tokenContracts(chain: String): List<TokenContract> =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select mint_address, decimals
                      from octo.instrument
                     where chain = ? and mint_address is not null
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.executeQuery().use { r ->
                        buildList {
                            while (r.next()) {
                                add(TokenContract(r.getString("mint_address"), r.getInt("decimals")))
                            }
                        }
                    }
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
            insert into octo.onchain_transfer
                (external_id, chain, signature, slot, block_hash, block_time, commitment,
                 wallet, counterparty, token_account, mint_address, amount_raw, decimals,
                 direction, transfer_kind, vendor_payload,
                 source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, 'finalized', ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?, ?)
            on conflict (source_system, external_id) do nothing
            """.trimIndent()
        // TODO(#114): carry the normalized provider payload into vendor_payload when the webhook
        // path lands — lineage then covers both delivery routes.
        return dataSource.scoped(TenantScope.All) { c ->
            val rows = omitSolanaFeesAlreadyBooked(transfers, stagedLegacyBalances(c, transfers))
            if (rows.isEmpty()) return@scoped 0
            c.prepareStatement(sql).use { s ->
                for (t in rows) {
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
                    s.setString(15, t.sourceSystem)
                    s.setString(16, actor)
                    s.setObject(17, ingestionRunId)
                    s.setObject(18, correlationId)
                    s.addBatch()
                }
                s.executeBatch().count { it > 0 }
            }
        }
    }

    /**
     * Staged `bal:0` legs that a Solana fee in this batch might already be inside. One indexed
     * lookup per source system; batches with no fee leg do not touch the table.
     */
    private fun stagedLegacyBalances(
        connection: Connection,
        transfers: List<OnchainTransfer>,
    ): List<StagedNativeLeg> {
        val wanted =
            transfers
                .filter { it.isSolanaNetworkFee() }
                .mapNotNull { fee -> legacyBalanceExternalId(fee.externalId)?.let { fee.sourceSystem to it } }
                .groupBy({ it.first }, { it.second })
        if (wanted.isEmpty()) return emptyList()
        val sql =
            """
            select source_system, external_id, amount_raw, direction
              from octo.onchain_transfer
             where source_system = ?
               and external_id = any (?)
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            buildList {
                for ((sourceSystem, ids) in wanted) {
                    statement.setString(1, sourceSystem)
                    statement.setArray(2, connection.createArrayOf("text", ids.distinct().toTypedArray()))
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            val direction =
                                TransferDirection.entries.firstOrNull { it.db == rows.getString("direction") }
                                    ?: continue
                            add(
                                StagedNativeLeg(
                                    sourceSystem = rows.getString("source_system"),
                                    externalId = rows.getString("external_id"),
                                    amountRaw = rows.getBigDecimal("amount_raw").toBigIntegerExact(),
                                    direction = direction,
                                ),
                            )
                        }
                    }
                }
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
            insert into octo.onchain_balance_snapshot
                (external_id, as_of, chain, wallet, token_account, mint_address,
                 amount_raw, decimals, usd_value, source, slot,
                 source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (source_system, external_id) do nothing
            """.trimIndent()
        return dataSource.scoped(TenantScope.All) { c ->
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
                    s.setString(12, b.sourceSystem)
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
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select distinct on (coalesce(token_account, mint_address))
                           wallet, token_account, mint_address, amount_raw, decimals,
                           usd_value, source, slot, as_of
                      from octo.onchain_balance_snapshot s
                     where chain = ? and wallet = ?
                       and not exists (
                           select 1 from octo.onchain_balance_snapshot x
                            where x.supersedes_id = s.id)
                     order by coalesce(token_account, mint_address), as_of desc
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

    override fun insertEvidence(
        evidence: List<OnchainEvidence>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        if (evidence.isEmpty()) return 0
        return dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    insert into octo.onchain_claim_evidence
                        (external_id, claim_ref, chain, subject_address, evidence_kind,
                         observed_numeric, observed_text, observed_payload, as_of,
                         source_system, actor, ingestion_run_id, correlation_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    on conflict (source_system, external_id) do nothing
                    """.trimIndent(),
                ).use { s ->
                    for (e in evidence) {
                        s.setString(1, e.externalId)
                        s.setString(2, e.claimRef)
                        s.setString(3, e.chain)
                        s.setString(4, e.subjectAddress)
                        s.setString(5, e.kind.db)
                        s.setBigDecimal(6, e.observedNumeric)
                        s.setString(7, e.observedText)
                        s.setString(8, e.payload.toString())
                        s.setTimestamp(9, Timestamp.from(e.asOf))
                        s.setString(10, e.sourceSystem)
                        s.setString(11, actor)
                        s.setObject(12, ingestionRunId)
                        s.setObject(13, correlationId)
                        s.addBatch()
                    }
                    s.executeBatch().sumOf { if (it >= 0) it else 0 }
                }
        }
    }

    // The observation's identity: an identical report in the same second is the same fact;
    // a different amount or a second source is a different observation worth keeping. Stake
    // accounts share a wallet and (null) mint, so a row with a token account is keyed by it too;
    // rows without one keep their original id so existing data stays deduplicated.
    internal fun snapshotExternalId(b: OnchainBalance): String =
        "${b.chain}:${b.wallet}:${b.mintAddress ?: "native"}${b.tokenAccount?.let { ":$it" } ?: ""}" +
            ":balance:${b.source.db}:${b.asOf.epochSecond}:${b.amountRaw}"
}
