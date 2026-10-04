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
    fun `re-staging a legacy fee-inclusive balance does not insert a second fee leg`() {
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        val signature = sig()
        val legacy =
            transfer(wallet, signature, 250_000_010L).copy(
                amountRaw = BigInteger("1000005000"),
                direction = TransferDirection.OUT,
                transferKind = TransferKind.TRANSFER_OUT,
            )
        assertThat(store.insertTransfers(listOf(legacy), UUID.randomUUID(), UUID.randomUUID(), "helius-poller")).isEqualTo(1)

        assertThat(store.insertTransfers(feeSplit(legacy, "1000000000", "5000"), UUID.randomUUID(), UUID.randomUUID(), "helius-webhook"))
            .isZero()
        assertThat(stagedAmounts(signature)).containsExactly("out" to "1000005000")
    }

    @Test
    fun `a legacy native debit of exactly the fee is not paired with a new fee leg`() {
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        val signature = sig()
        val legacy =
            transfer(wallet, signature, 250_000_011L).copy(
                amountRaw = BigInteger("5000"),
                direction = TransferDirection.OUT,
                transferKind = TransferKind.TRANSFER_OUT,
            )
        store.insertTransfers(listOf(legacy), UUID.randomUUID(), UUID.randomUUID(), "helius-poller")

        val feeOnly =
            legacy.copy(
                externalId = "solana:$signature:$wallet:fee",
                direction = TransferDirection.FEE,
            )
        assertThat(store.insertTransfers(listOf(feeOnly), UUID.randomUUID(), UUID.randomUUID(), "helius-webhook")).isZero()
        assertThat(stagedAmounts(signature)).containsExactly("out" to "5000")
    }

    @Test
    fun `a fee-free balance staged without its fee still accepts the fee leg`() {
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        val signature = sig()
        val balance =
            transfer(wallet, signature, 250_000_012L).copy(
                amountRaw = BigInteger("1000000000"),
                direction = TransferDirection.OUT,
                transferKind = TransferKind.TRANSFER_OUT,
            )
        store.insertTransfers(listOf(balance), UUID.randomUUID(), UUID.randomUUID(), "helius-poller")

        assertThat(store.insertTransfers(feeSplit(balance, "1000000000", "5000"), UUID.randomUUID(), UUID.randomUUID(), "helius-webhook"))
            .isEqualTo(1)
        assertThat(stagedAmounts(signature)).containsExactly(
            "fee" to "5000",
            "out" to "1000000000",
        )
    }

    @Test
    fun `a fee-split transaction with no legacy row stages the balance and the fee`() {
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        val signature = sig()
        val balance =
            transfer(wallet, signature, 250_000_013L).copy(
                amountRaw = BigInteger("1000000000"),
                direction = TransferDirection.OUT,
                transferKind = TransferKind.TRANSFER_OUT,
            )
        val split = feeSplit(balance, "1000000000", "5000")
        assertThat(store.insertTransfers(split, UUID.randomUUID(), UUID.randomUUID(), "helius-poller")).isEqualTo(2)
        assertThat(store.insertTransfers(split, UUID.randomUUID(), UUID.randomUUID(), "helius-webhook")).isZero()
        assertThat(stagedAmounts(signature)).containsExactly(
            "fee" to "5000",
            "out" to "1000000000",
        )
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
        assertThat(store.newestSlot(CHAIN_SOLANA, wallet)).isEqualTo(250_000_009L)
        assertThat(store.newestSlot(CHAIN_SOLANA, addr())).isNull()
    }

    @Test
    fun `newestSlot ignores staking rewards so the poller cursor never skips unscanned history`() {
        val wallet = addr()
        track(wallet)
        event(wallet, "watched")
        store.insertTransfers(
            listOf(
                transfer(wallet, "scanned", 250_000_001L),
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
        assertThat(store.newestSlot(CHAIN_SOLANA, wallet)).isEqualTo(250_000_001L)
    }

    @Test
    fun `newestStagedSlot is the highest staged slot on the chain`() {
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
    fun `the EVM scan checkpoint advances and never moves backward`() {
        val chain = "checkpoint-it"
        assertThat(store.scannedThrough(chain)).isNull()
        store.recordScannedThrough(chain, 100)
        assertThat(store.scannedThrough(chain)).isEqualTo(100L)
        store.recordScannedThrough(chain, 100)
        store.recordScannedThrough(chain, 40)
        assertThat(store.scannedThrough(chain)).isEqualTo(100L)
        store.recordScannedThrough(chain, 250)
        assertThat(store.scannedThrough(chain)).isEqualTo(250L)
        assertThat(store.scannedThrough("checkpoint-other")).isNull()
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

    private fun sig() = "sig" + UUID.randomUUID().toString().replace("-", "")

    private fun feeSplit(
        balance: OnchainTransfer,
        balanceAmount: String,
        feeAmount: String,
    ) = listOf(
        balance.copy(amountRaw = BigInteger(balanceAmount)),
        balance.copy(
            externalId = "solana:${balance.signature}:${balance.wallet}:fee",
            amountRaw = BigInteger(feeAmount),
            direction = TransferDirection.FEE,
            transferKind = TransferKind.TRANSFER_OUT,
        ),
    )

    private fun stagedAmounts(signature: String): List<Pair<String, String>> =
        dataSource().connection.use { c ->
            c
                .prepareStatement(
                    "select direction, amount_raw from octo.onchain_transfer where signature = ? order by direction",
                ).use { s ->
                    s.setString(1, signature)
                    s.executeQuery().use { r ->
                        buildList {
                            while (r.next()) {
                                add(r.getString("direction") to r.getBigDecimal("amount_raw").toBigIntegerExact().toString())
                            }
                        }
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
