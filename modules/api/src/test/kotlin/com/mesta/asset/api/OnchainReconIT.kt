package com.mesta.asset.api

import com.mesta.asset.iborcore.InstrumentFlowPromoter
import com.mesta.asset.iborcore.persistence.JdbcInstrumentFlowStore
import com.mesta.asset.iborcore.tokenPositions
import com.mesta.asset.ingestion.onchain.BalanceSource
import com.mesta.asset.ingestion.onchain.OnchainBalance
import com.mesta.asset.ingestion.onchain.OnchainTransfer
import com.mesta.asset.ingestion.onchain.TransferDirection
import com.mesta.asset.ingestion.onchain.TransferKind
import com.mesta.asset.ingestion.onchain.persistence.JdbcOnchainStagingStore
import com.mesta.asset.recon.ObservedBalance
import com.mesta.asset.recon.reconcileOnchain
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/**
 * End-to-end recon on the real V10 schema: staged transfers promote to `instrument_flow`,
 * snapshots stage and read back latest-per-mint, and `reconcileOnchain` diffs the two —
 * the safety net the megaplan names for wrong facts in staging.
 */
@Testcontainers(disabledWithoutDocker = true)
class OnchainReconIT {
    private val migrated by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("mesta")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        Unit
    }

    private val staging by lazy {
        migrated
        JdbcOnchainStagingStore(dataSource())
    }
    private val flowStore by lazy {
        migrated
        JdbcInstrumentFlowStore(dataSource())
    }
    private val instruments by lazy { flowStore.instrumentIds() }

    @Test
    fun `matching snapshot and promoted flows reconcile clean`() {
        val wallet = addr()
        stage(transfer(wallet, "sig1", "solana:sig1:$wallet:bal:0", 500), transfer(wallet, "sig2", "solana:sig2:$wallet:bal:0", -200))
        InstrumentFlowPromoter(flowStore).promote()
        insertSnapshot(wallet, null, 300)

        val report =
            reconcileOnchain(
                positions = tokenPositions(flowStore.flowsFor("solana", wallet), Instant.now()),
                snapshots = staging.latestSnapshots("solana", wallet).map(::toObserved),
                instruments = instruments,
            )

        assertThat(report.clean).isTrue()
    }

    @Test
    fun `an onchain holding the ledger misses surfaces as a positive delta`() {
        val wallet = addr()
        stage(transfer(wallet, "sig1", "solana:sig1:$wallet:bal:0", 500))
        InstrumentFlowPromoter(flowStore).promote()
        insertSnapshot(wallet, null, 750)

        val report =
            reconcileOnchain(
                positions = tokenPositions(flowStore.flowsFor("solana", wallet), Instant.now()),
                snapshots = staging.latestSnapshots("solana", wallet).map(::toObserved),
                instruments = instruments,
            )

        assertThat(report.clean).isFalse()
        assertThat(report.divergent.single().deltaRaw).isEqualByComparingTo(BigInteger("250"))
    }

    @Test
    fun `a staged staking reward promotes to flow and counts in the native position`() {
        val wallet = addr()
        val stakeAccount = addr()
        stage(
            OnchainTransfer(
                externalId = "solana:700:$stakeAccount",
                signature = "reward:700:$stakeAccount",
                slot = 305_587_200,
                blockHash = null,
                blockTime = Instant.now(),
                wallet = wallet,
                counterparty = null,
                tokenAccount = stakeAccount,
                mintAddress = null,
                amountRaw = BigInteger("1234"),
                decimals = 9,
                direction = TransferDirection.IN,
                transferKind = TransferKind.STAKING_REWARD,
            ),
        )
        InstrumentFlowPromoter(flowStore).promote()
        insertSnapshot(wallet, null, 1234)

        val report =
            reconcileOnchain(
                positions = tokenPositions(flowStore.flowsFor("solana", wallet), Instant.now()),
                snapshots = staging.latestSnapshots("solana", wallet).map(::toObserved),
                instruments = instruments,
            )

        assertThat(report.clean).isTrue()
        assertThat(
            flowStore
                .flowsFor("solana", wallet)
                .single()
                .flowType.wireValue,
        ).isEqualTo("staking-reward")
    }

    @Test
    fun `latestSnapshots returns one row per mint and identical observations dedupe`() {
        val wallet = addr()
        val balance = balance(wallet, null, 100)
        val run = UUID.randomUUID()

        assertThat(staging.insertSnapshots(listOf(balance), run, UUID.randomUUID(), "test")).isEqualTo(1)
        assertThat(staging.insertSnapshots(listOf(balance), run, UUID.randomUUID(), "test")).isZero()
        insertSnapshot(wallet, null, 200, asOf = Instant.parse("2025-06-02T00:00:00Z"))

        val latest = staging.latestSnapshots("solana", wallet)
        assertThat(latest).hasSize(1)
        assertThat(latest.single().amountRaw).isEqualByComparingTo(BigInteger("200"))
    }

    private fun dataSource() =
        run {
            migrated
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        }

    private fun addr() =
        "7VVV" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .replace(Regex("[0OIl]"), "A")
                .take(39)

    private fun stage(vararg transfers: OnchainTransfer) =
        staging.insertTransfers(transfers.toList(), UUID.randomUUID(), UUID.randomUUID(), "helius-poller")

    private fun transfer(
        wallet: String,
        signature: String,
        externalId: String,
        lamports: Long,
    ) = OnchainTransfer(
        externalId = externalId,
        signature = signature,
        slot = 250_000_000L + lamports,
        blockHash = "bh",
        blockTime = Instant.now(),
        wallet = wallet,
        counterparty = null,
        tokenAccount = null,
        mintAddress = null,
        amountRaw = BigInteger.valueOf(kotlin.math.abs(lamports)),
        decimals = 9,
        direction = if (lamports >= 0) TransferDirection.IN else TransferDirection.OUT,
        transferKind = if (lamports >= 0) TransferKind.TRANSFER_IN else TransferKind.TRANSFER_OUT,
    )

    private fun balance(
        wallet: String,
        mint: String?,
        amount: Long,
        asOf: Instant = Instant.parse("2025-06-01T00:00:00Z"),
    ) = OnchainBalance(
        wallet = wallet,
        tokenAccount = null,
        mintAddress = mint,
        amountRaw = BigInteger.valueOf(amount),
        decimals = 9,
        usdValue = null,
        source = BalanceSource.WALLET_API,
        slot = null,
        asOf = asOf,
    )

    private fun insertSnapshot(
        wallet: String,
        mint: String?,
        amount: Long,
        asOf: Instant = Instant.parse("2025-06-01T00:00:00Z"),
    ) = staging.insertSnapshots(
        listOf(balance(wallet, mint, amount, asOf)),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "helius-balance-collector",
    )

    private fun toObserved(b: OnchainBalance) =
        ObservedBalance(
            chain = b.chain,
            wallet = b.wallet,
            mintAddress = b.mintAddress,
            amountRaw = b.amountRaw,
            decimals = b.decimals,
            asOf = b.asOf,
            source = b.source.db,
        )

    private companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")
    }
}
