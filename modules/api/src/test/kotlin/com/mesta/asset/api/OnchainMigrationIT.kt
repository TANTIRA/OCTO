package com.mesta.asset.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/**
 * Exercises the V10 onchain tables the way `LedgerMigrationIT` exercises the fiat ledger:
 * append-only triggers, idempotency keys, the finalized-commitment gate, Solana address
 * shapes, and the tracked-address watch/unwatch replay rules.
 */
@Testcontainers(disabledWithoutDocker = true)
class OnchainMigrationIT {
    private val connection: Connection by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("mesta")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    @Test
    fun `migration creates the onchain tables and seeds native SOL`() {
        for (table in listOf(
            "instrument",
            "tracked_address",
            "tracked_address_event",
            "onchain_transfer",
            "onchain_balance_snapshot",
            "instrument_flow",
        )) {
            assertThat(
                count(
                    "select count(*) from information_schema.tables " +
                        "where table_schema = 'mesta' and table_name = '$table'",
                ),
            ).describedAs("mesta.%s after migration", table).isEqualTo(1)
        }
        assertThat(
            count(
                "select count(*) from mesta.instrument " +
                    "where external_key = 'solana:native' and instrument_kind = 'native-token' " +
                    "and mint_address is null and decimals = 9",
            ),
        ).describedAs("seeded native SOL instrument").isEqualTo(1)
    }

    @Test
    fun `the arbitrum instruments are seeded — native ETH and both USDC deployments`() {
        assertThat(
            count(
                "select count(*) from mesta.instrument " +
                    "where external_key = 'arbitrum-one:native' and instrument_kind = 'native-token' " +
                    "and mint_address is null and decimals = 18",
            ),
        ).describedAs("seeded native ETH instrument").isEqualTo(1)
        // Native USDC and bridged USDC.e are distinct instruments keyed by contract —
        // never merged by symbol.
        assertThat(
            count(
                "select count(*) from mesta.instrument " +
                    "where chain = 'arbitrum-one' and instrument_kind = 'erc20' " +
                    "and mint_address in ('0xaf88d065e77c8cc2239327c5edb3a432268e5831', " +
                    "'0xff970a61a04b1ca14834a43f5de4533ebddb5cc8')",
            ),
        ).describedAs("seeded USDC and USDC.e instruments").isEqualTo(2)
    }

    @Test
    fun `an evm transfer on an arbitrum chain is accepted`() {
        assertThat(insertEvmTransfer()).isNotNull()
    }

    @Test
    fun `an evm-shaped wallet is rejected on solana and vice versa`() {
        assertThatThrownBy { insertTransfer(wallet = evmWallet()) }
            .isInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertEvmTransfer(wallet = trackedAddress()) }
            .isInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertEvmTransfer(mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v") }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `an unfinalized transfer is still rejected on an arbitrum chain`() {
        assertThatThrownBy { insertEvmTransfer(commitment = "safe") }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a finalized transfer is accepted`() {
        assertThat(insertTransfer()).isNotNull()
    }

    @Test
    fun `an unfinalized transfer is rejected`() {
        assertThatThrownBy { insertTransfer(commitment = "confirmed") }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a malformed wallet address is rejected`() {
        assertThatThrownBy { insertTransfer(wallet = "0xO0Il_not_base58") }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a replayed transfer does not insert twice`() {
        val externalId = "solana:sig${UUID.randomUUID()}:acct:i0"
        insertTransfer(externalId = externalId)
        assertThatThrownBy { insertTransfer(externalId = externalId) }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `update on the staging table is rejected by the append-only trigger`() {
        val id = insertTransfer()
        assertThatThrownBy {
            connection.createStatement().use {
                it.executeUpdate("update mesta.onchain_transfer set direction = 'out' where id = '$id'")
            }
        }.isInstanceOf(SQLException::class.java).hasMessageContaining("append-only")
    }

    @Test
    fun `an unknown mint does not silently resolve to an instrument`() {
        val instrumentId = nativeInstrumentId()
        assertThatThrownBy {
            insertFlow(instrumentId = UUID.randomUUID(), externalId = "x-${UUID.randomUUID()}")
        }.isInstanceOf(SQLException::class.java)
        assertThat(insertFlow(instrumentId = instrumentId, externalId = "y-${UUID.randomUUID()}")).isNotNull()
    }

    @Test
    fun `superseding a flow without a rationale is rejected`() {
        val instrumentId = nativeInstrumentId()
        val original = insertFlow(instrumentId = instrumentId, externalId = "orig-${UUID.randomUUID()}")
        assertThatThrownBy {
            insertFlow(instrumentId = instrumentId, externalId = "fix-${UUID.randomUUID()}", supersedesId = original)
        }.isInstanceOf(SQLException::class.java)
        assertThat(
            insertFlow(
                instrumentId = instrumentId,
                externalId = "fix2-${UUID.randomUUID()}",
                supersedesId = original,
                rationale = "Reclassify as airdrop.",
            ),
        ).isNotNull()
    }

    @Test
    fun `an unwatch before any watch is rejected`() {
        val address = trackedAddress()
        insertTrackedAddress(address)
        assertThatThrownBy { insertWatchEvent(address, "unwatched", "done") }
            .isInstanceOf(SQLException::class.java)
        insertWatchEvent(address, "watched")
        assertThatThrownBy { insertWatchEvent(address, "watched") }
            .isInstanceOf(SQLException::class.java)
        assertThat(count("select count(*) from mesta.tracked_address_event where address = '$address'")).isEqualTo(1)
    }

    @Test
    fun `a balance snapshot keeps provider usd_value out of the ledger tables`() {
        val sql =
            """
            insert into mesta.onchain_balance_snapshot
                (external_id, as_of, chain, wallet, mint_address, amount_raw, decimals,
                 usd_value, source, source_system, actor, ingestion_run_id, correlation_id)
            values (?, now(), 'solana', ?, null, 1000000000, 9, 41.25, 'wallet-api',
                    'test', 'integration-test', ?, ?)
            returning id
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, "snap-${UUID.randomUUID()}")
            statement.setString(2, trackedAddress())
            statement.setObject(3, UUID.randomUUID())
            statement.setObject(4, UUID.randomUUID())
            statement.executeQuery().use { assertThat(it.next()).isTrue() }
        }
        assertThatThrownBy {
            connection.createStatement().use {
                it.executeUpdate(
                    "insert into mesta.onchain_balance_snapshot " +
                        "(external_id, as_of, chain, wallet, amount_raw, decimals, source, " +
                        "source_system, actor, ingestion_run_id, correlation_id) " +
                        "values ('bad-src', now(), 'solana', '${trackedAddress()}', 1, 9, 'coinbase', " +
                        "'test', 't', gen_random_uuid(), gen_random_uuid())",
                )
            }
        }.isInstanceOf(SQLException::class.java)
    }

    private fun count(sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun nativeInstrumentId(): UUID =
        connection.createStatement().use { statement ->
            statement.executeQuery("select id from mesta.instrument where external_key = 'solana:native'").use {
                it.next()
                it.getObject(1, UUID::class.java)
            }
        }

    // Valid base58: no 0/O/I/l, 32-44 chars.
    private fun trackedAddress() = "7${"V".repeat(3)}${"A".repeat(39)}"

    // Valid EVM address: 0x + 40 lowercase hex.
    private fun evmWallet() = "0x${UUID.randomUUID().toString().replace("-", "")}${"a".repeat(8)}"

    private fun insertTrackedAddress(address: String) {
        connection.createStatement().use {
            it.executeUpdate(
                "insert into mesta.tracked_address (chain, address, source_system, correlation_id) " +
                    "values ('solana', '$address', 'test', gen_random_uuid())",
            )
        }
    }

    private fun insertWatchEvent(
        address: String,
        type: String,
        rationale: String? = null,
    ) {
        connection.createStatement().use {
            it.executeUpdate(
                "insert into mesta.tracked_address_event " +
                    "(chain, address, event_type, actor, rationale, occurred_at, correlation_id) " +
                    "values ('solana', '$address', '$type', 'integration-test', " +
                    "${rationale?.let { r -> "'$r'" } ?: "null"}, now(), gen_random_uuid())",
            )
        }
    }

    private fun insertTransfer(
        externalId: String = "solana:sig-${UUID.randomUUID()}:acct:i0",
        commitment: String = "finalized",
        wallet: String = trackedAddress(),
    ): UUID? {
        val sql =
            """
            insert into mesta.onchain_transfer
                (external_id, chain, signature, slot, block_time, commitment, wallet,
                 amount_raw, decimals, direction, transfer_kind,
                 source_system, actor, ingestion_run_id, correlation_id)
            values (?, 'solana', ?, ?, now(), ?, ?, 500, 9, 'in', 'transfer-in',
                    'test', 'integration-test', ?, ?)
            returning id
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, externalId)
            statement.setString(2, "sig${UUID.randomUUID().toString().replace("-", "")}")
            statement.setLong(3, 250_000_000L)
            statement.setString(4, commitment)
            statement.setString(5, wallet)
            statement.setObject(6, UUID.randomUUID())
            statement.setObject(7, UUID.randomUUID())
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.getObject(1, UUID::class.java) else null
            }
        }
    }

    private fun insertEvmTransfer(
        externalId: String = "arbitrum-one:0xtx-${UUID.randomUUID()}:w:log:0",
        commitment: String = "finalized",
        wallet: String = evmWallet(),
        mint: String? = "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
    ): UUID? {
        val sql =
            """
            insert into mesta.onchain_transfer
                (external_id, chain, signature, slot, block_time, commitment, wallet,
                 mint_address, amount_raw, decimals, direction, transfer_kind,
                 source_system, actor, ingestion_run_id, correlation_id)
            values (?, 'arbitrum-one', ?, ?, now(), ?, ?, ?, 250000000, 6, 'in', 'transfer-in',
                    'rpc-arbitrum-one', 'integration-test', ?, ?)
            returning id
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, externalId)
            statement.setString(2, "0x${UUID.randomUUID().toString().replace("-", "")}${"b".repeat(32)}")
            statement.setLong(3, 42_000_000L)
            statement.setString(4, commitment)
            statement.setString(5, wallet)
            statement.setString(6, mint)
            statement.setObject(7, UUID.randomUUID())
            statement.setObject(8, UUID.randomUUID())
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.getObject(1, UUID::class.java) else null
            }
        }
    }

    private fun insertFlow(
        instrumentId: UUID,
        externalId: String,
        supersedesId: UUID? = null,
        rationale: String? = null,
    ): UUID? {
        val sql =
            """
            insert into mesta.instrument_flow
                (external_id, instrument_id, chain, wallet, flow_type, amount_raw, decimals,
                 occurred_at, supersedes_id, rationale,
                 source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, 'solana', ?, 'transfer-in', 500, 9, now(), ?, ?,
                    'test', 'integration-test', ?, ?)
            returning id
            """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, externalId)
            statement.setObject(2, instrumentId)
            statement.setString(3, trackedAddress())
            if (supersedesId == null) {
                statement.setNull(4, Types.OTHER)
            } else {
                statement.setObject(4, supersedesId)
            }
            statement.setString(5, rationale)
            statement.setObject(6, UUID.randomUUID())
            statement.setObject(7, UUID.randomUUID())
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.getObject(1, UUID::class.java) else null
            }
        }
    }

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
