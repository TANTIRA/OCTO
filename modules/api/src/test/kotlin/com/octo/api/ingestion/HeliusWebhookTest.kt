package com.octo.api.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.OctoApplication
import com.octo.ingestion.onchain.FinalityProbe
import com.octo.ingestion.onchain.OnchainBalance
import com.octo.ingestion.onchain.OnchainEvidence
import com.octo.ingestion.onchain.OnchainStagingStore
import com.octo.ingestion.onchain.OnchainTransfer
import com.octo.ingestion.onchain.OnchainWebhookService
import com.octo.ingestion.onchain.TokenContract
import com.octo.ingestion.onchain.TransactionFetcher
import com.octo.ingestion.onchain.WatchSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

private const val WEBHOOK = "/api/v1/ingestion/webhooks/helius"
private const val SECRET = "whsec-test"
private val WATCHED = "7VVA" + "D".repeat(39)
private val SIG = "5wHuPkQ" + "v".repeat(80)

private val delivery =
    """
    [{
      "slot": 250000000,
      "blockTime": 1726000000,
      "transaction": {
        "signatures": ["$SIG"],
        "message": {"recentBlockhash": "bh123", "accountKeys": [{"pubkey": "$WATCHED", "signer": false}]}
      },
      "meta": {
        "err": null,
        "preBalances": [1000],
        "postBalances": [2000000000],
        "preTokenBalances": [],
        "postTokenBalances": []
      }
    }]
    """.trimIndent()

private class RecordingStore : OnchainStagingStore {
    val transfers = mutableListOf<OnchainTransfer>()

    override fun activeWatchedAddresses(chain: String) = listOf(WatchSource(chain, WATCHED, null, null))

    override fun newestSlot(
        chain: String,
        wallet: String,
    ): Long? = null

    override fun watchedTokenAccounts(chain: String): Map<String, String> = emptyMap()

    override fun newestStagedSlot(chain: String): Long? = null

    override fun tokenContracts(chain: String): List<TokenContract> = emptyList()

    override fun insertTransfers(
        transfers: List<OnchainTransfer>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int {
        this.transfers += transfers
        return transfers.size
    }

    override fun insertSnapshots(
        balances: List<OnchainBalance>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ) = balances.size

    override fun latestSnapshots(
        chain: String,
        wallet: String,
    ): List<OnchainBalance> = emptyList()

    override fun insertEvidence(
        evidence: List<OnchainEvidence>,
        ingestionRunId: UUID,
        correlationId: UUID,
        actor: String,
    ): Int = evidence.size
}

class HeliusWebhookTest {
    /** Stands in for the chain's own copy: the test delivery normalizes the same either way. */
    private val canonicalFetcher = TransactionFetcher { sig -> ObjectMapper().readTree(delivery)[0].takeIf { sig == SIG } }

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
                "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.group.readiness.include=readinessState",
            )

    private fun mvc(context: WebApplicationContext) =
        MockMvcBuilders
            .webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun postDelivery(
        context: WebApplicationContext,
        secret: String? = SECRET,
    ) = mvc(context).perform(
        post(WEBHOOK)
            .contentType(MediaType.APPLICATION_JSON)
            .content(delivery)
            .apply { if (secret != null) header("Authorization", secret) },
    )

    @Test
    fun `a verified delivery is normalized and staged`() {
        val store = RecordingStore()
        contextRunner
            .withPropertyValues("HELIUS_WEBHOOK_SECRET=$SECRET")
            .withBean(OnchainStagingStore::class.java, { store })
            .withBean(FinalityProbe::class.java, { FinalityProbe { it.toSet() } })
            .withBean(TransactionFetcher::class.java, { canonicalFetcher })
            .run { context ->
                postDelivery(context).andExpect(status().isOk)
                assertThat(store.transfers).hasSize(1)
                assertThat(store.transfers.single().externalId).isEqualTo("solana:$SIG:$WATCHED:bal:0")
            }
    }

    @Test
    fun `a delivery that is not yet finalized is held and acknowledged`() {
        val store = RecordingStore()
        contextRunner
            .withPropertyValues("HELIUS_WEBHOOK_SECRET=$SECRET")
            .withBean(OnchainStagingStore::class.java, { store })
            .withBean(FinalityProbe::class.java, { FinalityProbe { emptySet() } })
            .run { context ->
                postDelivery(context).andExpect(status().isOk)
                assertThat(store.transfers).isEmpty()
            }
    }

    @Test
    fun `a delivery refused by a full re-check set answers 503 so Helius retries`() {
        val store = RecordingStore()
        val fullSetService =
            OnchainWebhookService(
                store,
                FinalityProbe { emptySet() },
                canonicalFetcher,
                maxDeferred = 0,
            )
        contextRunner
            .withPropertyValues("HELIUS_WEBHOOK_SECRET=$SECRET")
            .withBean(OnchainStagingStore::class.java, { store })
            .withBean(OnchainWebhookService::class.java, { fullSetService })
            .run { context ->
                postDelivery(context).andExpect(status().isServiceUnavailable)
                assertThat(store.transfers).isEmpty()
            }
    }

    @Test
    fun `a missing secret is rejected`() {
        contextRunner
            .withPropertyValues("HELIUS_WEBHOOK_SECRET=$SECRET")
            .run { context -> postDelivery(context, secret = null).andExpect(status().isUnauthorized) }
    }

    @Test
    fun `a wrong secret is rejected`() {
        contextRunner
            .withPropertyValues("HELIUS_WEBHOOK_SECRET=$SECRET")
            .run { context -> postDelivery(context, secret = "whsec-other").andExpect(status().isUnauthorized) }
    }

    @Test
    fun `with no secret configured the endpoint fails closed`() {
        contextRunner.run { context ->
            postDelivery(context).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `the webhook secret opens nothing on the JWT chain`() {
        contextRunner
            .withPropertyValues("HELIUS_WEBHOOK_SECRET=$SECRET")
            .run { context ->
                mvc(context)
                    .perform(post("/api/funds").header("Authorization", SECRET))
                    .andExpect(status().isForbidden)
            }
    }
}
