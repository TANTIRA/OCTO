package com.mesta.asset.api.ingestion

import com.mesta.asset.ingestion.onchain.evm.EvmBalanceCollector
import com.mesta.asset.ingestion.onchain.evm.EvmScanService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * Runs the EVM poller on a fixed delay — the scheduling entry point the Solana services
 * never grew (they are webhook-fed today). One poll scans the finalized range for ERC-20
 * `Transfer` legs, then snapshots watched balances at the same head. A failed poll logs
 * and waits for the next tick; staging rows already written are facts, so the next run
 * resumes rather than redoes.
 */
class EvmSyncRunner(
    private val scan: EvmScanService,
    private val balances: EvmBalanceCollector,
    meters: MeterRegistry?,
) {
    private val log = LoggerFactory.getLogger(EvmSyncRunner::class.java)
    private val legsStaged = counter(meters, "onchain.evm.scan.legs_staged")
    private val logsSeen = counter(meters, "onchain.evm.scan.logs_seen")
    private val windowsScanned = counter(meters, "onchain.evm.scan.windows")
    private val windowShrinks = counter(meters, "onchain.evm.scan.window_shrinks")
    private val skippedContracts = counter(meters, "onchain.evm.scan.skipped_contracts")
    private val snapshotsInserted = counter(meters, "onchain.evm.balance.snapshots")
    private val failedWallets = counter(meters, "onchain.evm.balance.failed_wallets")
    private val pollErrors = counter(meters, "onchain.evm.poll.errors")

    @Scheduled(
        fixedDelayString = "\${ARBITRUM_POLL_MS:60000}",
        initialDelayString = "\${ARBITRUM_POLL_INITIAL_DELAY_MS:15000}",
    )
    fun poll() {
        try {
            val report = scan.scan()
            legsStaged?.increment(report.legsStaged.toDouble())
            logsSeen?.increment(report.logsSeen.toDouble())
            windowsScanned?.increment(report.windowsScanned.toDouble())
            windowShrinks?.increment(report.windowShrinks.toDouble())
            skippedContracts?.increment(report.skippedContracts.size.toDouble())
            if (report.logsSeen > 0 || report.skippedContracts.isNotEmpty() || report.windowShrinks > 0) {
                log.info(
                    "evm scan {}: {} legs from {} logs across {} windows (head {}, {} shrinks, skipped contracts {})",
                    report.chain,
                    report.legsStaged,
                    report.logsSeen,
                    report.windowsScanned,
                    report.headBlock,
                    report.windowShrinks,
                    report.skippedContracts,
                )
            }

            val balances = balances.collect()
            snapshotsInserted?.increment(balances.snapshotsInserted.toDouble())
            failedWallets?.increment(balances.failedWallets.size.toDouble())
            if (balances.failedWallets.isNotEmpty()) {
                log.warn("evm balance collection: {} wallets failed: {}", balances.failedWallets.size, balances.failedWallets)
            }
        } catch (e: Exception) {
            pollErrors?.increment()
            log.warn("evm onchain poll failed: {}", e.message)
        }
    }

    private fun counter(
        meters: MeterRegistry?,
        name: String,
    ): Counter? = meters?.let { Counter.builder(name).register(it) }
}
