package com.mesta.asset.api.ingestion

import com.mesta.asset.ingestion.onchain.OnchainBalance
import com.mesta.asset.ingestion.onchain.OnchainEvidence
import com.mesta.asset.ingestion.onchain.OnchainStagingStore
import com.mesta.asset.ingestion.onchain.OnchainTransfer
import com.mesta.asset.ingestion.onchain.OnchainWebhookService
import com.mesta.asset.ingestion.onchain.TokenContract
import com.mesta.asset.ingestion.onchain.WatchSource
import com.mesta.asset.ingestion.onchain.persistence.JdbcOnchainStagingStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
            ): Long? = delegate.newestSlot(chain, wallet)

            override fun watchedTokenAccounts(chain: String): Map<String, String> = delegate.watchedTokenAccounts(chain)

            override fun newestStagedSlot(chain: String): Long? = delegate.newestStagedSlot(chain)

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

    @Bean
    fun onchainWebhookService(store: OnchainStagingStore) = OnchainWebhookService(store)
}
