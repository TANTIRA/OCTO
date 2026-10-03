package com.octo.ingestion.onchain.persistence

import com.octo.ingestion.onchain.BalanceSource
import com.octo.ingestion.onchain.OnchainBalance
import com.octo.ingestion.onchain.OnchainEvidence
import com.octo.ingestion.onchain.OnchainStagingStore
import com.octo.ingestion.onchain.OnchainTransfer
import com.octo.ingestion.onchain.SyncFrontier
import com.octo.ingestion.onchain.TokenContract
import com.octo.ingestion.onchain.TransferDirection
import com.octo.ingestion.onchain.TransferKind
import com.octo.ingestion.onchain.WatchSource
import com.octo.persistence.TenantScope
import com.octo.persistence.scoped
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.Timestamp
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
     * The newest slot this writer staged for a chain/wallet, or null when it staged nothing.
     *
     * The [actor] filter scopes the cursor to the caller's own pipeline (#509): rows written
     * by the webhook must not move the poller's `slot.gt` cursor, or a transaction delivered
     * before the first poll would skip the wallet's whole history below it.
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
        actor: String,
    ): Long? =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select max(slot)
                      from octo.onchain_transfer
                     where chain = ? and wallet = ? and actor = ? and transfer_kind <> ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
                    s.setString(3, actor)
                    s.setString(4, TransferKind.STAKING_REWARD.db)
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

    override fun syncFrontier(
        chain: String,
        wallet: String,
    ): SyncFrontier? =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select floor_slot, ceiling_slot
                      from octo.onchain_sync_frontier
                     where chain = ? and address = ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
                    s.executeQuery().use { r ->
                        if (r.next()) {
                            SyncFrontier(
                                chain = chain,
                                address = wallet,
                                floorSlot = r.getLong("floor_slot").takeIf { !r.wasNull() },
                                ceilingSlot = r.getLong("ceiling_slot"),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override fun saveSyncFrontier(frontier: SyncFrontier) {
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    insert into octo.onchain_sync_frontier (chain, address, floor_slot, ceiling_slot)
                    values (?, ?, ?, ?)
                    on conflict (chain, address) do update
                       set ceiling_slot = excluded.ceiling_slot,
                           floor_slot = excluded.floor_slot,
                           updated_at = now()
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, frontier.chain)
                    s.setString(2, frontier.address)
                    frontier.floorSlot?.let { s.setLong(3, it) } ?: s.setNull(3, java.sql.Types.BIGINT)
                    s.setLong(4, frontier.ceilingSlot)
                    s.executeUpdate()
                }
        }
    }

    override fun clearSyncFrontier(
        chain: String,
        wallet: String,
    ) {
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    delete from octo.onchain_sync_frontier
                     where chain = ? and address = ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, wallet)
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

    override fun scanCheckpoint(chain: String): Long? =
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    select through_block
                      from octo.onchain_scan_checkpoint
                     where chain = ?
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, chain)
                    s.executeQuery().use { r -> if (r.next()) r.getLong(1) else null }
                }
        }

    override fun saveScanCheckpoint(
        chain: String,
        block: Long,
    ) {
        dataSource.scoped(TenantScope.All) { c ->
            c
                .prepareStatement(
                    """
                    insert into octo.onchain_scan_checkpoint (chain, through_block)
                    values (?, ?)
                    on conflict (chain) do update
                       set through_block = greatest(octo.onchain_scan_checkpoint.through_block, excluded.through_block),
                           updated_at = now()
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
        // #553: a fee leg only stages when its sibling balance leg is absent or provably the
        // current normalization shape — the row the batch itself produces (same amount,
        // direction and kind). A transaction staged before fees split into their own leg kept
        // a `bal:0` whose amount already includes the fee (or one the current normalizer would
        // never write); inserting the fee leg on top would book the fee twice. Skipping keeps
        // the fee represented exactly once — inside the stored leg.
        val feeSql =
            """
            insert into octo.onchain_transfer
                (external_id, chain, signature, slot, block_hash, block_time, commitment,
                 wallet, counterparty, token_account, mint_address, amount_raw, decimals,
                 direction, transfer_kind, vendor_payload,
                 source_system, actor, ingestion_run_id, correlation_id)
            select *
              from (values (?, ?, ?, cast(? as bigint), ?, cast(? as timestamptz), 'finalized',
                            ?, ?, ?, ?, cast(? as numeric), cast(? as integer), ?, ?, cast(null as jsonb),
                            ?, ?, cast(? as uuid), cast(? as uuid))) as v
            where not exists (
                select 1
                  from octo.onchain_transfer sib
                 where sib.source_system = ?
                   and sib.external_id = ?
                   and (cast(? as numeric) is null
                        or sib.amount_raw <> cast(? as numeric)
                        or sib.direction <> ?
                        or sib.transfer_kind <> ?))
            on conflict (source_system, external_id) do nothing
            """.trimIndent()
        // TODO(#114): carry the normalized provider payload into vendor_payload when the webhook
        // path lands — lineage then covers both delivery routes.
        val byId = transfers.associateBy { it.externalId }
        return dataSource.scoped(TenantScope.All) { c ->
            var staged = 0
            c.prepareStatement(sql).use { s ->
                for (t in transfers) {
                    if (t.direction == TransferDirection.FEE) continue
                    s.setTransfer(t, actor, ingestionRunId, correlationId)
                    s.addBatch()
                }
                staged += s.executeBatch().count { it > 0 }
            }
            c.prepareStatement(feeSql).use { s ->
                for (t in transfers) {
                    if (t.direction != TransferDirection.FEE) continue
                    val sibling = byId[t.externalId.removeSuffix(FEE_LEG) + BAL_PAYER_LEG]
                    s.setTransfer(t, actor, ingestionRunId, correlationId)
                    s.setString(19, t.sourceSystem)
                    s.setString(20, t.externalId.removeSuffix(FEE_LEG) + BAL_PAYER_LEG)
                    if (sibling == null) {
                        s.setNull(21, java.sql.Types.NUMERIC)
                        s.setNull(22, java.sql.Types.NUMERIC)
                        s.setNull(23, java.sql.Types.VARCHAR)
                        s.setNull(24, java.sql.Types.VARCHAR)
                    } else {
                        s.setBigDecimal(21, sibling.amountRaw.toBigDecimal())
                        s.setBigDecimal(22, sibling.amountRaw.toBigDecimal())
                        s.setString(23, sibling.direction.db)
                        s.setString(24, sibling.transferKind.db)
                    }
                    staged += s.executeUpdate()
                }
            }
            staged
        }
    }

    private fun PreparedStatement.setTransfer(
        t: OnchainTransfer,
        actor: String,
        ingestionRunId: UUID,
        correlationId: UUID,
    ) {
        setString(1, t.externalId)
        setString(2, t.chain)
        setString(3, t.signature)
        setLong(4, t.slot)
        setString(5, t.blockHash)
        setTimestamp(6, Timestamp.from(t.blockTime))
        setString(7, t.wallet)
        setString(8, t.counterparty)
        setString(9, t.tokenAccount)
        setString(10, t.mintAddress)
        setBigDecimal(11, t.amountRaw.toBigDecimal())
        setInt(12, t.decimals)
        setString(13, t.direction.db)
        setString(14, t.transferKind.db)
        setString(15, t.sourceSystem)
        setString(16, actor)
        setObject(17, ingestionRunId)
        setObject(18, correlationId)
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

    private companion object {
        // The Helius normalizer's leg ids: "solana:<sig>:<account>:fee" sits next to
        // "solana:<sig>:<account>:bal:0" — the payer is always account index 0.
        const val FEE_LEG = ":fee"
        const val BAL_PAYER_LEG = ":bal:0"
    }
}
