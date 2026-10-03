package com.octo.api

import com.octo.ingestion.onchain.CHAIN_ARBITRUM_ONE
import com.octo.ingestion.onchain.CHAIN_SOLANA
import com.octo.ingestion.onchain.OnchainTransfer
import com.octo.ingestion.onchain.TransferDirection
import com.octo.ingestion.onchain.TransferKind
import com.octo.ingestion.onchain.persistence.JdbcOnchainStagingStore
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
 * End-to-end coverage of `JdbcOnchainStagingStore` against the real V10 schema: watch-state
 * derivation, idempotent staging inserts, and the signature cursor the poller resumes from.
 */
@Testcontainers(disabledWithoutDocker = true)
class OnchainStagingStoreIT {
    private val migrated by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("octo")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        Unit
    }

    private val store by lazy {
        migrated
        JdbcOnchainStagingStore(dataSource())
    }

    @Test
    fun `only addresses whose latest event is watched are active`() {
        val watched = addr()
        val unwatchable = addr()
        val neverWatched = addr()
        track(watched)
        track(unwatchable)
        track(neverWatched)
        event(watched, "watched")
        event(unwatchable, "watched")
        event(unwatchable, "unwatched", "rotated")

        assertThat(store.activeWatchedAddresses(CHAIN_SOLANA).map { it.address }).containsExactly(watched)
    }

    @Test
    fun `staged transfers dedupe on the source external key`() {
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        val legs = listOf(transfer(wallet, "sig1", 250_000_001L), transfer(wallet, "sig2", 250_000_002L))

        assertThat(store.insertTransfers(legs, UUID.randomUUID(), UUID.randomUUID(), "helius-poller")).isEqualTo(2)
        assertThat(store.insertTransfers(legs, UUID.randomUUID(), UUID.randomUUID(), "helius-poller")).isZero()
    }

    @Test
    fun `newestSlot returns the highest staged slot`() {
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        store.insertTransfers(
            listOf(
                transfer(wallet, "older", 250_000_001L),
                transfer(wallet, "newest", 250_000_009L),
                transfer(wallet, "middle", 250_000_005L),
            ),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "helius-poller",
        )
        assertThat(store.newestSlot(CHAIN_SOLANA, wallet, "helius-poller")).isEqualTo(250_000_009L)
        assertThat(store.newestSlot(CHAIN_SOLANA, addr(), "helius-poller")).isNull()
    }

    @Test
    fun `newestSlot is scoped to the writer's actor so webhook rows cannot move the poller cursor`() {
        // #509: a webhook-delivered transaction used to advance the same cursor, so the
        // poller's first pass skipped the wallet's history below it.
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        store.insertTransfers(
            listOf(transfer(wallet, "webhook", 250_000_900L)),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "helius-webhook",
        )

        assertThat(store.newestSlot(CHAIN_SOLANA, wallet, "helius-webhook")).isEqualTo(250_000_900L)
        assertThat(store.newestSlot(CHAIN_SOLANA, wallet, "helius-poller")).isNull()
    }

    @Test
    fun `newestSlot ignores staking rewards so the poller cursor never skips unscanned history`() {
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        store.insertTransfers(
            listOf(transfer(wallet, "scanned", 250_000_001L)),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "helius-poller",
        )
        store.insertTransfers(
            listOf(
                transfer(wallet, "reward:700:stake", 250_000_900L).copy(
                    externalId = "solana:700:stake-$wallet",
                    direction = TransferDirection.IN,
                    transferKind = TransferKind.STAKING_REWARD,
                ),
            ),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "helius-staking",
        )
        assertThat(store.newestSlot(CHAIN_SOLANA, wallet, "helius-poller")).isEqualTo(250_000_001L)
    }

    @Test
    fun `newestStagedSlot is the EVM resume cursor — the highest staged slot on the chain`() {
        val wallet = evmAddr()
        track(wallet, CHAIN_ARBITRUM_ONE)
        event(wallet, "watched", chain = CHAIN_ARBITRUM_ONE)
        store.insertTransfers(
            listOf(
                evmTransfer(wallet, "0xa", 100L),
                evmTransfer(wallet, "0xb", 250L),
                evmTransfer(wallet, "0xc", 140L),
            ),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "evm-poller",
        )
        // The container is shared across tests, so assert the floor then pin the max with a
        // slot no other row can exceed.
        assertThat(store.newestStagedSlot(CHAIN_ARBITRUM_ONE)).isGreaterThanOrEqualTo(250L)
        store.insertTransfers(
            listOf(evmTransfer(wallet, "0xtop", 777_777_777L)),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "evm-poller",
        )
        assertThat(store.newestStagedSlot(CHAIN_ARBITRUM_ONE)).isEqualTo(777_777_777L)
    }

    @Test
    fun `sync frontier persists per wallet until cleared`() {
        // #509: a wallet whose history exceeds the page budget resumes from this frontier on the
        // next poll instead of restarting from the newest cursor.
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")

        assertThat(store.syncFrontier(CHAIN_SOLANA, wallet)).isNull()
        store.saveSyncFrontier(
            com.octo.ingestion.onchain.SyncFrontier(
                chain = CHAIN_SOLANA,
                address = wallet,
                floorSlot = 250_000_010L,
                ceilingSlot = 250_000_090L,
            ),
        )
        assertThat(store.syncFrontier(CHAIN_SOLANA, wallet))
            .isEqualTo(
                com.octo.ingestion.onchain.SyncFrontier(
                    chain = CHAIN_SOLANA,
                    address = wallet,
                    floorSlot = 250_000_010L,
                    ceilingSlot = 250_000_090L,
                ),
            )
        // A second partial descent overwrites the frontier in place.
        store.saveSyncFrontier(
            com.octo.ingestion.onchain.SyncFrontier(
                chain = CHAIN_SOLANA,
                address = wallet,
                floorSlot = 250_000_010L,
                ceilingSlot = 250_000_050L,
            ),
        )
        assertThat(store.syncFrontier(CHAIN_SOLANA, wallet)?.ceilingSlot).isEqualTo(250_000_050L)
        store.clearSyncFrontier(CHAIN_SOLANA, wallet)
        assertThat(store.syncFrontier(CHAIN_SOLANA, wallet)).isNull()
    }

    @Test
    fun `scan checkpoint persists per chain`() {
        // #494: the EVM cursor lives in the checkpoint table, not in staged rows, so quiet
        // ranges still advance the resume point.
        store.saveScanCheckpoint(CHAIN_ARBITRUM_ONE, 123L)
        assertThat(store.scanCheckpoint(CHAIN_ARBITRUM_ONE)).isEqualTo(123L)
        store.saveScanCheckpoint(CHAIN_ARBITRUM_ONE, 456L)
        assertThat(store.scanCheckpoint(CHAIN_ARBITRUM_ONE)).isEqualTo(456L)
    }

    @Test
    fun `a fee leg is refused when the sibling leg predates the fee split`() {
        // #553: staged under the old normalization, the payer's bal:0 already contains the
        // fee. Re-normalizing splits the same transaction into a fee-free bal leg plus a fee
        // leg; the bal leg dedupes and the fee leg must be refused or the fee books twice.
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        val oldShape =
            transfer(wallet, "sigMixed", 250_000_001L).copy(
                externalId = "solana:sigMixed:$wallet:bal:0",
                direction = TransferDirection.OUT,
                transferKind = TransferKind.TRANSFER_OUT,
                amountRaw = BigInteger("6000"),
            )
        store.insertTransfers(listOf(oldShape), UUID.randomUUID(), UUID.randomUUID(), "helius-poller")

        val newShapeBal =
            transfer(wallet, "sigMixed", 250_000_001L).copy(
                externalId = "solana:sigMixed:$wallet:bal:0",
                direction = TransferDirection.OUT,
                transferKind = TransferKind.TRANSFER_OUT,
                amountRaw = BigInteger("1000"),
            )
        val feeLeg = feeTransfer(wallet, "sigMixed", 250_000_001L, BigInteger("5000"))
        assertThat(
            store.insertTransfers(listOf(newShapeBal, feeLeg), UUID.randomUUID(), UUID.randomUUID(), "helius-poller"),
        ).isZero()

        assertThat(stagedTotal("sigMixed")).isEqualTo(BigInteger("6000"))
    }

    @Test
    fun `a fee leg is refused for a pure-fee transaction staged before the split`() {
        // #553: the old shape wrote the whole fee as a bal:0 out leg; the new shape writes no
        // bal leg at all, so nothing in the batch proves the stored row is the current shape.
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        val oldShape =
            transfer(wallet, "sigPureFee", 250_000_002L).copy(
                externalId = "solana:sigPureFee:$wallet:bal:0",
                direction = TransferDirection.OUT,
                transferKind = TransferKind.TRANSFER_OUT,
                amountRaw = BigInteger("5000"),
            )
        store.insertTransfers(listOf(oldShape), UUID.randomUUID(), UUID.randomUUID(), "helius-poller")

        val feeLeg = feeTransfer(wallet, "sigPureFee", 250_000_002L, BigInteger("5000"))
        assertThat(
            store.insertTransfers(listOf(feeLeg), UUID.randomUUID(), UUID.randomUUID(), "helius-poller"),
        ).isZero()

        assertThat(stagedTotal("sigPureFee")).isEqualTo(BigInteger("5000"))
    }

    @Test
    fun `a fee leg stages with its sibling under one normalization shape`() {
        // The common path: both legs land together on first delivery, and a redelivery of the
        // same new-shape batch dedupes instead of suppressing the fee.
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        val bal =
            transfer(wallet, "sigNew", 250_000_003L).copy(
                externalId = "solana:sigNew:$wallet:bal:0",
                direction = TransferDirection.OUT,
                transferKind = TransferKind.TRANSFER_OUT,
                amountRaw = BigInteger("1000"),
            )
        val fee = feeTransfer(wallet, "sigNew", 250_000_003L, BigInteger("5000"))

        assertThat(store.insertTransfers(listOf(bal, fee), UUID.randomUUID(), UUID.randomUUID(), "helius-poller"))
            .isEqualTo(2)
        assertThat(store.insertTransfers(listOf(bal, fee), UUID.randomUUID(), UUID.randomUUID(), "helius-poller"))
            .isZero()
        assertThat(stagedTotal("sigNew")).isEqualTo(BigInteger("6000"))
    }

    @Test
    fun `tokenContracts returns registered non-native instruments on the chain`() {
        assertThat(store.tokenContracts(CHAIN_ARBITRUM_ONE).map { it.mintAddress })
            .containsExactlyInAnyOrder(
                "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
                "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8",
            )
    }

    @Test
    fun `sourceSystem is written from the row, not a constant`() {
        val wallet = evmAddr()
        track(wallet, CHAIN_ARBITRUM_ONE)
        event(wallet, "watched", chain = CHAIN_ARBITRUM_ONE)
        store.insertTransfers(
            listOf(evmTransfer(wallet, "0xsrc", 300L)),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "evm-poller",
        )
        dataSource().connection.use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select source_system from octo.onchain_transfer where chain = 'arbitrum-one' and signature = '0xsrc'",
                    ).use { r ->
                        assertThat(r.next()).isTrue()
                        assertThat(r.getString(1)).isEqualTo("rpc-arbitrum-one")
                    }
            }
        }
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

    private fun evmAddr() = "0x${UUID.randomUUID().toString().replace("-", "")}${"a".repeat(8)}"

    private fun feeTransfer(
        wallet: String,
        signature: String,
        slot: Long,
        amount: BigInteger,
    ) = transfer(wallet, signature, slot).copy(
        externalId = "solana:$signature:$wallet:fee",
        direction = TransferDirection.FEE,
        transferKind = TransferKind.TRANSFER_OUT,
        amountRaw = amount,
    )

    private fun stagedTotal(signature: String): BigInteger =
        dataSource().connection.use { c ->
            c
                .prepareStatement("select coalesce(sum(amount_raw), 0) from octo.onchain_transfer where signature = ?")
                .use { s ->
                    s.setString(1, signature)
                    s.executeQuery().use { r ->
                        r.next()
                        r.getBigDecimal(1).toBigInteger()
                    }
                }
        }

    private fun track(
        address: String,
        chain: String = CHAIN_SOLANA,
    ) {
        dataSource().connection.use { c ->
            c
                .prepareStatement(
                    "insert into octo.tracked_address (chain, address, source_system, correlation_id) values (?, ?, 'test', ?)",
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, address)
                    s.setObject(3, UUID.randomUUID())
                    s.executeUpdate()
                }
        }
    }

    private fun event(
        address: String,
        type: String,
        rationale: String? = null,
        chain: String = CHAIN_SOLANA,
    ) {
        dataSource().connection.use { c ->
            c
                .prepareStatement(
                    "insert into octo.tracked_address_event (chain, address, event_type, actor, rationale, occurred_at, correlation_id) " +
                        "values (?, ?, ?, 'test', ?, now(), ?)",
                ).use { s ->
                    s.setString(1, chain)
                    s.setString(2, address)
                    s.setString(3, type)
                    s.setString(4, rationale)
                    s.setObject(5, UUID.randomUUID())
                    s.executeUpdate()
                }
        }
    }

    private fun transfer(
        wallet: String,
        signature: String,
        slot: Long,
    ) = OnchainTransfer(
        externalId = "solana:$signature:$wallet:bal:0",
        signature = signature,
        slot = slot,
        blockHash = "bh",
        blockTime = Instant.now(),
        wallet = wallet,
        counterparty = null,
        tokenAccount = null,
        mintAddress = null,
        amountRaw = BigInteger("500"),
        decimals = 9,
        direction = TransferDirection.IN,
        transferKind = TransferKind.TRANSFER_IN,
    )

    private fun evmTransfer(
        wallet: String,
        signature: String,
        slot: Long,
    ) = OnchainTransfer(
        externalId = "$CHAIN_ARBITRUM_ONE:$signature:$wallet:log:0",
        signature = signature,
        slot = slot,
        blockHash = "0xblock",
        blockTime = Instant.now(),
        wallet = wallet,
        counterparty = "0x9999999999999999999999999999999999999999",
        tokenAccount = null,
        mintAddress = "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
        amountRaw = BigInteger("250000000"),
        decimals = 6,
        direction = TransferDirection.IN,
        transferKind = TransferKind.TRANSFER_IN,
        chain = CHAIN_ARBITRUM_ONE,
        sourceSystem = "rpc-arbitrum-one",
    )

    private companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("octo")
                .withUsername("octo")
                .withPassword("octo")
    }
}
