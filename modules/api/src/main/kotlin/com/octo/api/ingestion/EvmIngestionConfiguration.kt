package com.mesta.asset.api.ingestion

import com.mesta.asset.ingestion.onchain.CHAIN_ARBITRUM_ONE
import com.mesta.asset.ingestion.onchain.OnchainStagingStore
import com.mesta.asset.ingestion.onchain.evm.EvmBalanceCollector
import com.mesta.asset.ingestion.onchain.evm.EvmConfig
import com.mesta.asset.ingestion.onchain.evm.EvmEvidenceAdapter
import com.mesta.asset.ingestion.onchain.evm.EvmRpcApi
import com.mesta.asset.ingestion.onchain.evm.EvmRpcClient
import com.mesta.asset.ingestion.onchain.evm.EvmScanService
import com.mesta.asset.ingestion.onchain.evm.EvmTransferNormalizer
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Wires the EVM onchain adapter. Everything hangs off `ARBITRUM_RPC_URL`: unset means the
 * whole slice stays out of the context — no beans, no scheduler, no half-configured scan.
 * The composition root may reach into `onchain.evm` the same way it already constructs
 * `JdbcOnchainStagingStore`; the ModuleBoundaryTest rule keeps every other module out.
 */
@Configuration(proxyBeanMethods = false)
class EvmIngestionConfiguration {
    @Bean
    @ConditionalOnProperty("ARBITRUM_RPC_URL")
    fun arbitrumEvmConfig(env: Environment): EvmConfig =
        EvmConfig(
            rpcBaseUrl = env.getRequiredProperty("ARBITRUM_RPC_URL"),
            chain = CHAIN_ARBITRUM_ONE,
            chainId = env.getProperty("ARBITRUM_CHAIN_ID", Long::class.java, ARBITRUM_ONE_CHAIN_ID),
            startBlock = env.getProperty("ARBITRUM_START_BLOCK", Long::class.java, 0L),
            maxBlockWindow = env.getProperty("ARBITRUM_MAX_BLOCK_WINDOW", Long::class.java, 10_000L),
            apiKey = env.getProperty("ARBITRUM_API_KEY")?.takeIf(String::isNotBlank),
        )

    @Bean
    @ConditionalOnBean(EvmConfig::class)
    fun evmRpcClient(config: EvmConfig): EvmRpcApi = EvmRpcClient(config)

    @Bean
    @ConditionalOnBean(EvmRpcApi::class)
    fun evmScanService(
        rpc: EvmRpcApi,
        store: OnchainStagingStore,
        config: EvmConfig,
    ) = EvmScanService(rpc, EvmTransferNormalizer(), store, config)

    @Bean
    @ConditionalOnBean(EvmRpcApi::class)
    fun evmBalanceCollector(
        rpc: EvmRpcApi,
        store: OnchainStagingStore,
        config: EvmConfig,
    ) = EvmBalanceCollector(store, rpc, config)

    @Bean
    @ConditionalOnBean(EvmRpcApi::class)
    fun evmEvidenceAdapter(
        rpc: EvmRpcApi,
        config: EvmConfig,
    ) = EvmEvidenceAdapter(rpc, config)

    @Bean
    @ConditionalOnBean(EvmRpcApi::class)
    fun evmSyncRunner(
        scan: EvmScanService,
        balances: EvmBalanceCollector,
        meters: ObjectProvider<MeterRegistry>,
    ) = EvmSyncRunner(scan, balances, meters.getIfAvailable())

    private companion object {
        const val ARBITRUM_ONE_CHAIN_ID = 42161L
    }
}
