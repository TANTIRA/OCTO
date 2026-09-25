package com.mesta.asset.api

import com.mesta.asset.iborcore.InstrumentFlowPromoter
import com.mesta.asset.iborcore.InstrumentFlowType
import com.mesta.asset.iborcore.persistence.JdbcInstrumentFlowStore
import com.mesta.asset.iborcore.tokenPositions
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
 * End-to-end coverage of staging -> instrument_flow promotion over the real V10 schema:
 * finalized rows promote under their staging identity, unknown mints quarantine, replays
 * no-op, and a staged correction supersedes the flow its original produced.
 */
@Testcontainers(disabledWithoutDocker = true)
class OnchainPromotionIT {
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
    private val store by lazy {
        migrated
        JdbcInstrumentFlowStore(dataSource())
    }
    private val promoter by lazy { InstrumentFlowPromoter(store) }

    @Test
    fun `a finalized SOL transfer promotes and the position derives from the flow`() {
        val wallet = addr()
        stage(transfer(wallet, "sig-a", "solana:sig-a:$wallet:bal:0", 500), transfer(wallet, "sig-b", "solana:sig-b:$wallet:bal:0", -200))

        val report = promoter.promote()

        assertThat(report.promoted).isEqualTo(2)
        val flows = store.flowsFor("solana", wallet)
        assertThat(flows.map { it.flowType }).containsExactlyInAnyOrder(InstrumentFlowType.TRANSFER_IN, InstrumentFlowType.TRANSFER_OUT)
        assertThat(flows.all { it.instrumentId == nativeInstrumentId() }).isTrue()
        val position = tokenPositions(flows, Instant.now()).single()
        assertThat(position.netRaw).isEqualByComparingTo(BigInteger("300"))
    }

    @Test
    fun `an unregistered mint quarantines and promotes once the instrument exists`() {
        val wallet = addr()
        val mint = addr()
        stage(tokenTransfer(wallet, mint, "sig-t", "solana:sig-t:$mint:tok:1"))

        val first = promoter.promote()

        assertThat(first.promoted).isZero()
        assertThat(first.quarantined.map { it.mintAddress }).containsExactly(mint)

        registerMint(mint)
        val second = promoter.promote()

        assertThat(second.promoted).isEqualTo(1)
        assertThat(store.flowsFor("solana", wallet).single().instrumentId).isEqualTo(mintInstrumentId(mint))
    }

    @Test
    fun `a second promotion pass inserts nothing`() {
        val wallet = addr()
        stage(transfer(wallet, "sig-r", "solana:sig-r:$wallet:bal:0", 100))

        assertThat(promoter.promote().promoted).isEqualTo(1)
        assertThat(promoter.promote().promoted).isZero()
        assertThat(store.flowsFor("solana", wallet)).hasSize(1)
    }

    @Test
    fun `a staged correction supersedes the flow its original produced`() {
        val wallet = addr()
        val original = transfer(wallet, "sig-c", "solana:sig-c:$wallet:bal:0", 100)
        stage(original)
        val stagedId = stagingIdOf(original.externalId)
        stageCorrection(stagedId, wallet, "solana:sig-c:$wallet:bal:0:fix", 150)

        val report = promoter.promote()

        assertThat(report.promoted).isEqualTo(2)
        assertThat(report.deferred).isEmpty()
        val flows = store.flowsFor("solana", wallet)
        val originalFlow = flows.first { it.externalId == original.externalId }
        val correctionFlow = flows.first { it.externalId.endsWith(":fix") }
        assertThat(correctionFlow.supersedesId).isEqualTo(originalFlow.id)
        assertThat(correctionFlow.rationale).isEqualTo("restated amount")
        val position = tokenPositions(flows, Instant.now()).single()
        assertThat(position.netRaw).isEqualByComparingTo(BigInteger("150"))
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

    private fun tokenTransfer(
        wallet: String,
        mint: String,
        signature: String,
        externalId: String,
    ) = OnchainTransfer(
        externalId = externalId,
        signature = signature,
        slot = 250_000_100L,
        blockHash = "bh",
        blockTime = Instant.now(),
        wallet = wallet,
        counterparty = null,
        tokenAccount = addr(),
        mintAddress = mint,
        amountRaw = BigInteger("1000"),
        decimals = 6,
        direction = TransferDirection.IN,
        transferKind = TransferKind.TRANSFER_IN,
    )

    private fun registerMint(mint: String) {
        dataSource().connection.use { c ->
            c
                .prepareStatement(
                    """
                    insert into mesta.instrument
                        (external_key, chain, mint_address, instrument_kind, decimals, symbol,
                         source_system, actor, ingestion_run_id, correlation_id)
                    values (?, 'solana', ?, 'spl-token', 6, 'TST', 'test', 'test', ?, ?)
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, "solana:mint:$mint")
                    s.setString(2, mint)
                    s.setObject(3, UUID.randomUUID())
                    s.setObject(4, UUID.randomUUID())
                    s.executeUpdate()
                }
        }
    }

    private fun stageCorrection(
        supersedesId: UUID,
        wallet: String,
        externalId: String,
        lamports: Long,
    ) {
        dataSource().connection.use { c ->
            c
                .prepareStatement(
                    """
                    insert into mesta.onchain_transfer
                        (external_id, chain, signature, slot, block_time, commitment, wallet,
                         amount_raw, decimals, direction, transfer_kind,
                         supersedes_id, rationale, source_system, actor, ingestion_run_id, correlation_id)
                    values (?, 'solana', 'sig-c', 250000200, now(), 'finalized', ?,
                            ?, 9, 'in', 'transfer-in', ?, 'restated amount', 'helius-solana', 'helius-poller', ?, ?)
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, externalId)
                    s.setString(2, wallet)
                    s.setLong(3, lamports)
                    s.setObject(4, supersedesId)
                    s.setObject(5, UUID.randomUUID())
                    s.setObject(6, UUID.randomUUID())
                    s.executeUpdate()
                }
        }
    }

    private fun stagingIdOf(externalId: String): UUID =
        dataSource().connection.use { c ->
            c.prepareStatement("select id from mesta.onchain_transfer where external_id = ?").use { s ->
                s.setString(1, externalId)
                s.executeQuery().use { r ->
                    check(r.next()) { "staging row $externalId not found" }
                    r.getObject("id", UUID::class.java)
                }
            }
        }

    private fun nativeInstrumentId(): UUID = instrumentIdFor("solana:native")

    private fun mintInstrumentId(mint: String): UUID = instrumentIdFor("solana:mint:$mint")

    private fun instrumentIdFor(externalKey: String): UUID =
        dataSource().connection.use { c ->
            c.prepareStatement("select id from mesta.instrument where external_key = ?").use { s ->
                s.setString(1, externalKey)
                s.executeQuery().use { r ->
                    check(r.next()) { "instrument $externalKey not found" }
                    r.getObject("id", UUID::class.java)
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
