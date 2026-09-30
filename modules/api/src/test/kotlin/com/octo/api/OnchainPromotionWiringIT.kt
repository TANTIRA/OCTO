package com.octo.api

import com.octo.api.ingestion.OnchainPromotionRunner
import com.octo.iborcore.InstrumentFlowStore
import com.octo.ingestion.onchain.OnchainStagingStore
import com.octo.ingestion.onchain.OnchainTransfer
import com.octo.ingestion.onchain.TransferDirection
import com.octo.ingestion.onchain.TransferKind
import com.octo.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/**
 * #310: the booted application promotes what its own staging bean wrote — the beans the api
 * wires, not hand-built stores. The scheduler is pushed out of the test window so the explicit
 * `poll()` is the pass under test. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OnchainPromotionWiringIT {
    @Autowired
    private lateinit var staging: OnchainStagingStore

    @Autowired
    private lateinit var flows: InstrumentFlowStore

    @Autowired
    private lateinit var runner: OnchainPromotionRunner

    @Test
    fun `a transfer staged through the api's store is promoted by the scheduled runner`() {
        val wallet =
            "7VVV" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .replace(Regex("[0OIl]"), "A")
        val transfer =
            OnchainTransfer(
                externalId = "solana:sig-w:$wallet:bal:0",
                signature = "sig-w",
                slot = 250_000_000L,
                blockHash = "bh",
                blockTime = Instant.now(),
                wallet = wallet,
                counterparty = null,
                tokenAccount = null,
                mintAddress = null,
                amountRaw = BigInteger("42"),
                decimals = 9,
                direction = TransferDirection.IN,
                transferKind = TransferKind.TRANSFER_IN,
            )
        staging.insertTransfers(listOf(transfer), UUID.randomUUID(), UUID.randomUUID(), "helius-webhook")

        runner.poll()
        runner.poll()

        val promoted = flows.flowsFor("solana", wallet, TenantScope.All)
        assertThat(promoted.map { it.externalId }).containsExactly(transfer.externalId)
        assertThat(promoted.single().amountRaw).isEqualTo(BigInteger("42"))
    }

    companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("octo")
                .withUsername("octo")
                .withPassword("octo")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("DB_HOST", postgres::getHost)
            registry.add("DB_PORT") { postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) }
            registry.add("DB_NAME", postgres::getDatabaseName)
            registry.add("DB_USER", postgres::getUsername)
            registry.add("DB_PASSWORD", postgres::getPassword)
            registry.add("octo.onchain.promotion.initial-delay-ms") { 3_600_000 }
        }
    }
}
