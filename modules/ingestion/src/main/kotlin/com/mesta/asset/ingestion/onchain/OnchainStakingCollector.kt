package com.mesta.asset.ingestion.onchain

import com.mesta.asset.ingestion.onchain.helius.HeliusException
import com.mesta.asset.ingestion.onchain.helius.HeliusRpcApi
import com.mesta.asset.ingestion.onchain.helius.HeliusStakingNormalizer
import java.time.Instant
import java.util.UUID

/** What one staking `collect` pass did, for logs and the operator runbook. */
data class StakingCollectionReport(
    val walletsSeen: Int,
    val stakeAccountsSeen: Int,
    val rewardRowsInserted: Int,
    val snapshotRowsInserted: Int,
    val failedWallets: List<String>,
)

/**
 * Collects stake-account state and epoch rewards for every watched wallet.
 *
 * Two facts per account per pass: the delegated-balance observation (`onchain_balance_snapshot`
 * rows with `source='rpc'`, `token_account` = stake account) and each `getInflationReward`
 * entry staged as a `staking-reward` transfer — which promotion turns into `instrument_flow`
 * income with no extra mapping (`flow_type` derives 1:1 from `transfer_kind`).
 *
 * A failing wallet lands in [StakingCollectionReport.failedWallets] and never blocks the run;
 * epoch-idempotent reward ids make replays free.
 */
class OnchainStakingCollector(
    private val staging: OnchainStagingStore,
    private val rpc: HeliusRpcApi,
    private val normalizer: HeliusStakingNormalizer = HeliusStakingNormalizer(),
) {
    fun collect(
        chain: String = CHAIN_SOLANA,
        epoch: Long? = null,
        asOf: Instant = Instant.now(),
        ingestionRunId: UUID = UUID.randomUUID(),
    ): StakingCollectionReport {
        var accountsSeen = 0
        var rewardRows = 0
        var snapshotRows = 0
        val failed = mutableListOf<String>()
        val wallets = staging.activeWatchedAddresses(chain)
        for (watch in wallets) {
            try {
                val accounts = normalizer.stakeAccounts(rpc.stakeAccounts(watch.address), watch.address)
                accountsSeen += accounts.size
                if (accounts.isEmpty()) continue

                val addresses = accounts.map { it.stakeAccount }
                val rewards =
                    normalizer.rewards(
                        rpc.inflationReward(addresses, epoch),
                        watch.address,
                        addresses,
                        rpc::blockTime,
                        asOf,
                    )
                rewardRows += staging.insertTransfers(rewards, ingestionRunId, UUID.randomUUID(), ACTOR)
                snapshotRows +=
                    staging.insertSnapshots(
                        accounts.map { it.toSnapshot(asOf) },
                        ingestionRunId,
                        UUID.randomUUID(),
                        ACTOR,
                    )
            } catch (e: HeliusException) {
                failed += watch.address
            }
        }
        return StakingCollectionReport(wallets.size, accountsSeen, rewardRows, snapshotRows, failed)
    }

    private fun StakeAccountInfo.toSnapshot(asOf: Instant) =
        OnchainBalance(
            wallet = wallet,
            tokenAccount = stakeAccount,
            mintAddress = null,
            amountRaw = delegatedStakeRaw,
            decimals = SOL_DECIMALS,
            usdValue = null,
            source = BalanceSource.RPC,
            slot = null,
            asOf = asOf,
        )

    companion object {
        const val ACTOR = "helius-staking-collector"
        const val SOL_DECIMALS = 9
    }
}
