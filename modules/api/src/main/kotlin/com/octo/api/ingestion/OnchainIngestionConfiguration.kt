package com.octo.api.ingestion

import com.octo.api.graph.enqueueInstrumentFlowProjection
import com.octo.iborcore.InstrumentFlow
import com.octo.iborcore.InstrumentFlowPromoter
import com.octo.iborcore.InstrumentFlowStore
import com.octo.iborcore.persistence.JdbcInstrumentFlowStore
import com.octo.ingestion.onchain.FinalityProbe
import com.octo.ingestion.onchain.OnchainBalance
import com.octo.ingestion.onchain.OnchainEvidence
import com.octo.ingestion.onchain.OnchainStagingStore
import com.octo.ingestion.onchain.OnchainTransfer
import com.octo.ingestion.onchain.OnchainWebhookService
import com.octo.ingestion.onchain.TokenContract
import com.octo.ingestion.onchain.TransactionFetcher
import com.octo.ingestion.onchain.WatchSource
import com.octo.ingestion.onchain.persistence.JdbcOnchainStagingStore
import com.octo.persistence.TenantScope
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.util.UUID
import javax.sql.DataSource

/**
 * Wires the ingestion module's onchain staging into the api. The store resolves lazily — same
 * pattern as `AccessConfiguration` — so contexts without a datasource (test slices, readiness
 * during a lost database) still boot; the first use is what fails.
 */
@Configuration(proxyBeanMethods = false)
class OnchainIngestionConfiguration {
    @Bean
    @ConditionalOnMissingBean(OnchainStagingStore::class)
    fun onchainStagingStore(dataSource: ObjectProvider<DataSource>): OnchainStagingStore {
        val delegate by lazy { JdbcOnchainStagingStore(dataSource.getObject()) }
        return object : OnchainStagingStore {
            override fun activeWatchedAddresses(chain: String): List<WatchSource> = delegate.activeWatchedAddresses(chain)

            override fun newestSlot(
                chain: String,
                wallet: String,
                actor: String,
            ): Long? = delegate.newestSlot(chain, wallet, actor)

            override fun syncFrontier(
                chain: String,
                wallet: String,
            ) = delegate.syncFrontier(chain, wallet)

            override fun saveSyncFrontier(frontier: com.octo.ingestion.onchain.SyncFrontier) = delegate.saveSyncFrontier(frontier)

            override fun clearSyncFrontier(
                chain: String,
                wallet: String,
            ) = delegate.clearSyncFrontier(chain, wallet)

            override fun newestStagedSlot(chain: String): Long? = delegate.newestStagedSlot(chain)

            override fun scanCheckpoint(chain: String): Long? = delegate.scanCheckpoint(chain)

            override fun saveScanCheckpoint(
                chain: String,
                block: Long,
            ) = delegate.saveScanCheckpoint(chain, block)

            override fun tokenContracts(chain: String): List<TokenContract> = delegate.tokenContracts(chain)

            override fun insertTransfers(
                transfers: List<OnchainTransfer>,
                ingestionRunId: UUID,
                correlationId: UUID,
                actor: String,
            ): Int = delegate.insertTransfers(transfers, ingestionRunId, correlationId, actor)

            override fun insertSnapshots(
                balances: List<OnchainBalance>,
                ingestionRunId: UUID,
                correlationId: UUID,
                actor: String,
            ): Int = delegate.insertSnapshots(balances, ingestionRunId, correlationId, actor)

            override fun latestSnapshots(
                chain: String,
                wallet: String,
            ): List<OnchainBalance> = delegate.latestSnapshots(chain, wallet)

            override fun insertEvidence(
                evidence: List<OnchainEvidence>,
                ingestionRunId: UUID,
                correlationId: UUID,
                actor: String,
            ): Int = delegate.insertEvidence(evidence, ingestionRunId, correlationId, actor)
        }
    }

    /**
     * Helius finality probe — the only helius touchpoint api is allowed, since vendor types
     * stay inside the ingestion module (ADR-0001). Gated on `HELIUS_RPC_URL` like the EVM
     * slice hangs off `ARBITRUM_RPC_URL`.
     */
    @Bean
    @ConditionalOnProperty("HELIUS_RPC_URL")
    fun heliusFinalityProbe(env: Environment): FinalityProbe =
        FinalityProbe.helius(
            rpcBaseUrl = env.getRequiredProperty("HELIUS_RPC_URL"),
            apiKey = env.getRequiredProperty("HELIUS_API_KEY"),
            devnet = env.getProperty("HELIUS_NETWORK").equals("devnet", ignoreCase = true),
        )

    /**
     * Fail-closed stand-in when no RPC is configured (#167): a delivery that cannot be verified
     * answers 500 and Helius retries — never a staged maybe-fact. Keeps the context bootable so
     * a deployment without the webhook configured is unaffected.
     */
    @Bean
    @ConditionalOnMissingBean(FinalityProbe::class)
    fun unverifiedFinalityProbe(): FinalityProbe =
        FinalityProbe {
            error("webhook deliveries need HELIUS_RPC_URL/HELIUS_API_KEY for finality verification")
        }

    /**
     * Helius-backed transaction fetcher — the webhook honesty gate's other half (#316): staged
     * facts come from this canonical copy, never from the delivered payload's own content.
     */
    @Bean
    @ConditionalOnProperty("HELIUS_RPC_URL")
    fun heliusTransactionFetcher(env: Environment): TransactionFetcher =
        TransactionFetcher.helius(
            rpcBaseUrl = env.getRequiredProperty("HELIUS_RPC_URL"),
            apiKey = env.getRequiredProperty("HELIUS_API_KEY"),
            devnet = env.getProperty("HELIUS_NETWORK").equals("devnet", ignoreCase = true),
        )

    /** Fail-closed stand-in mirroring [unverifiedFinalityProbe] when no RPC is configured. */
    @Bean
    @ConditionalOnMissingBean(TransactionFetcher::class)
    fun unverifiedTransactionFetcher(): TransactionFetcher =
        TransactionFetcher {
            error("webhook deliveries need HELIUS_RPC_URL/HELIUS_API_KEY to fetch canonical transaction content")
        }

    @Bean
    @ConditionalOnMissingBean(OnchainWebhookService::class)
    fun onchainWebhookService(
        store: OnchainStagingStore,
        finality: FinalityProbe,
        transactions: TransactionFetcher,
    ) = OnchainWebhookService(store, finality, transactions)

    /**
     * Re-checks webhook deliveries held for finality on `octo.onchain.webhook.recheck-ms`
     * (default 5 s) (#483). Runs unless `octo.onchain.webhook.recheck.enabled` is false.
     */
    @Bean
    @ConditionalOnProperty("octo.onchain.webhook.recheck.enabled", havingValue = "true", matchIfMissing = true)
    fun onchainWebhookRecheckRunner(
        webhookService: OnchainWebhookService,
        meters: ObjectProvider<MeterRegistry>,
    ) = OnchainWebhookRecheckRunner(webhookService, meters.getIfAvailable())

    /** The token-ledger store, lazy like [onchainStagingStore] so datasource-less contexts still boot. */
    @Bean
    @ConditionalOnMissingBean(InstrumentFlowStore::class)
    fun instrumentFlowStore(dataSource: ObjectProvider<DataSource>): InstrumentFlowStore {
        val delegate by lazy {
            // #565: the flow's graph upsert rides the promotion transaction — the outbox row and
            // the ledger insert commit or roll back together.
            JdbcInstrumentFlowStore(dataSource.getObject(), ::enqueueInstrumentFlowProjection)
        }
        return object : InstrumentFlowStore {
            override fun promotableTransfers(limit: Int) = delegate.promotableTransfers(limit)

            override fun quarantinedMints() = delegate.quarantinedMints()

            override fun instrumentIds() = delegate.instrumentIds()

            override fun flowIdForStaging(stagingRowId: UUID) = delegate.flowIdForStaging(stagingRowId)

            override fun insertFlow(
                flow: InstrumentFlow,
                sourceSystem: String,
                ingestionRunId: UUID,
                correlationId: UUID,
            ) = delegate.insertFlow(flow, sourceSystem, ingestionRunId, correlationId)

            override fun flowsFor(
                chain: String,
                wallet: String,
                scope: TenantScope,
            ) = delegate.flowsFor(chain, wallet, scope)
        }
    }

    @Bean
    fun instrumentFlowPromoter(
        store: InstrumentFlowStore,
        env: Environment,
    ) = InstrumentFlowPromoter(
        store,
        batchSize = env.getProperty("octo.onchain.promotion.batch-size", Int::class.java, 500),
    )

    /** Staging → `instrument_flow` promotion (#310); runs unless `octo.onchain.promotion.enabled` is false. */
    @Bean
    @ConditionalOnProperty("octo.onchain.promotion.enabled", havingValue = "true", matchIfMissing = true)
    fun onchainPromotionRunner(
        promoter: InstrumentFlowPromoter,
        meters: ObjectProvider<MeterRegistry>,
    ) = OnchainPromotionRunner(promoter, meters.getIfAvailable())
}
