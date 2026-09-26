package com.mesta.asset.api

import com.mesta.asset.ingestion.onchain.CHAIN_ARBITRUM_ONE
import com.mesta.asset.ingestion.onchain.CHAIN_SOLANA
import com.mesta.asset.ingestion.onchain.OnchainTransfer
import com.mesta.asset.ingestion.onchain.TransferDirection
import com.mesta.asset.ingestion.onchain.TransferKind
import com.mesta.asset.ingestion.onchain.persistence.JdbcOnchainStagingStore
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
            .schemas("mesta")
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
    fun `newestSignature returns the signature at the highest staged slot`() {
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
        assertThat(store.newestSignature(CHAIN_SOLANA, wallet)).isEqualTo("newest")
        assertThat(store.newestSignature(CHAIN_SOLANA, addr())).isNull()
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
                s.executeQuery(
                    "select source_system from mesta.onchain_transfer where chain = 'arbitrum-one' and signature = '0xsrc'",
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
                    "insert into mesta.tracked_address (chain, address, source_system, correlation_id) values (?, ?, 'test', ?)",
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
                    "insert into mesta.tracked_address_event (chain, address, event_type, actor, rationale, occurred_at, correlation_id) " +
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
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")
    }
}
