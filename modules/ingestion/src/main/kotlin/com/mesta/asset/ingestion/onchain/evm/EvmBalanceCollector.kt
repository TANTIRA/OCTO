package com.mesta.asset.ingestion.onchain.evm

import com.mesta.asset.ingestion.onchain.BalanceSource
import com.mesta.asset.ingestion.onchain.OnchainBalance
import com.mesta.asset.ingestion.onchain.OnchainStagingStore
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** What one `collect` pass did, for logs, metrics, and the operator runbook. */
data class EvmBalanceReport(
    val walletsSeen: Int,
    val snapshotsInserted: Int,
    val skippedCalls: Int,
    val failedWallets: List<String>,
)

/**
 * Polls every watched wallet's holdings on an EVM chain into `onchain_balance_snapshot`
 * staging: `eth_getBalance` for the native asset (null mint) plus a `balanceOf` `eth_call`
 * per registered contract — all at the `finalized` block, so the snapshot and the
 * transfer legs observe the same world.
 *
 * `asOf` is the finalized block's own timestamp, not observation time: re-collecting the
 * same block reproduces the same `external_id` and dedupes on the staging key. A wallet
 * that fails lands in [EvmBalanceReport.failedWallets] and never blocks the rest of the
 * run — recon flags its stale snapshot on the next report.
 */
class EvmBalanceCollector(
    private val store: OnchainStagingStore,
    private val rpc: EvmRpcApi,
    private val config: EvmConfig,
    private val actor: String = "evm-balance-collector",
) {
    fun collect(ingestionRunId: UUID = UUID.randomUUID()): EvmBalanceReport {
        val wallets = store.activeWatchedAddresses(config.chain)
        if (wallets.isEmpty()) return EvmBalanceReport(0, 0, 0, emptyList())

        val block = rpc.finalizedBlock()
        val number = block.takeIf { it.isObject }?.path("number")
        if (number == null || !number.isTextual) {
            throw EvmException("endpoint does not expose a 'finalized' block tag for ${config.chain}")
        }
        val head = number.asQuantity().toLong()
        val asOf = Instant.ofEpochSecond(block.path("timestamp").asQuantity().toLong())
        val contracts = store.tokenContracts(config.chain)

        var snapshots = 0
        var skippedCalls = 0
        val failed = mutableListOf<String>()
        for (watch in wallets) {
            val wallet = watch.address.lowercase()
            try {
                val balances = mutableListOf<OnchainBalance>()
                val native = rpc.nativeBalance(wallet)
                if (native.signum() > 0) {
                    balances += snapshot(wallet, null, native, NATIVE_DECIMALS, head, asOf)
                }
                for (contract in contracts) {
                    val balance = rpc.balanceOf(contract.mintAddress, wallet)
                    if (balance == null) {
                        skippedCalls++
                        continue
                    }
                    if (balance.signum() > 0) {
                        balances += snapshot(wallet, contract.mintAddress.lowercase(), balance, contract.decimals, head, asOf)
                    }
                }
                snapshots += store.insertSnapshots(balances, ingestionRunId, UUID.randomUUID(), actor)
            } catch (e: EvmException) {
                failed += wallet
            }
        }
        return EvmBalanceReport(wallets.size, snapshots, skippedCalls, failed)
    }

    private fun snapshot(
        wallet: String,
        mint: String?,
        amountRaw: BigInteger,
        decimals: Int,
        slot: Long,
        asOf: Instant,
    ) = OnchainBalance(
        wallet = wallet,
        tokenAccount = null,
        mintAddress = mint,
        amountRaw = amountRaw,
        decimals = decimals,
        usdValue = null,
        source = BalanceSource.RPC,
        slot = slot,
        asOf = asOf,
        chain = config.chain,
        sourceSystem = config.sourceSystem,
    )

    private companion object {
        const val NATIVE_DECIMALS = 18
    }
}
