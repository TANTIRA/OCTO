package com.mesta.asset.api

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

    private fun track(address: String) {
        dataSource().connection.use { c ->
            c
                .prepareStatement(
                    "insert into mesta.tracked_address (chain, address, source_system, correlation_id) values ('solana', ?, 'test', ?)",
                ).use { s ->
                    s.setString(1, address)
                    s.setObject(2, UUID.randomUUID())
                    s.executeUpdate()
                }
        }
    }

    private fun event(
        address: String,
        type: String,
        rationale: String? = null,
    ) {
        dataSource().connection.use { c ->
            c
                .prepareStatement(
                    "insert into mesta.tracked_address_event (chain, address, event_type, actor, rationale, occurred_at, correlation_id) " +
                        "values ('solana', ?, ?, 'test', ?, now(), ?)",
                ).use { s ->
                    s.setString(1, address)
                    s.setString(2, type)
                    s.setString(3, rationale)
                    s.setObject(4, UUID.randomUUID())
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
