package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode
import com.mesta.asset.ingestion.onchain.OnchainTransfer
import com.mesta.asset.ingestion.onchain.StakeAccountInfo
import com.mesta.asset.ingestion.onchain.TransferDirection
import com.mesta.asset.ingestion.onchain.TransferKind
import java.math.BigInteger
import java.time.Instant

/**
 * `getProgramAccounts` (stake program) + `getInflationReward` -> normalized staking facts.
 *
 * Reward identity is `solana:<epoch>:<stakeAccount>` — one fact per account per epoch, so
 * re-collecting the same epoch (backfill, overlapping runs, poller/webhook-style retries)
 * collapses on the unique staging key instead of double-counting income.
 */
class HeliusStakingNormalizer {
    /** jsonParsed `getProgramAccounts` rows -> stake-account state for one watched wallet. */
    fun stakeAccounts(
        response: JsonNode,
        wallet: String,
    ): List<StakeAccountInfo> =
        response.mapNotNull { account ->
            val info =
                account
                    .path("account")
                    .path("data")
                    .path("parsed")
                    .path("info")
            val delegation = info.path("stake").path("delegation")
            StakeAccountInfo(
                stakeAccount = account.path("pubkey").asText().takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                wallet = wallet,
                voter = delegation.path("voter").asText(null),
                activationEpoch = delegation.path("activationEpoch").asText().toLongOrNull(),
                deactivationEpoch = delegation.path("deactivationEpoch").asText().toLongOrNull(),
                delegatedStakeRaw = delegation.path("stake").asText().toBigIntegerOrNull() ?: BigInteger.ZERO,
            )
        }

    /**
     * `getInflationReward` result slots -> staking-reward transfers. The RPC returns one slot
     * per queried address **in request order**; null slots mean the account earned nothing.
     *
     * [blockTimeOf] resolves `effectiveSlot` -> `block_time`; when it misses, the row falls
     * back to [observedAt] — the fact is the reward amount at the epoch, not the timestamp.
     */
    fun rewards(
        response: JsonNode,
        wallet: String,
        accounts: List<String>,
        blockTimeOf: (Long) -> Long?,
        observedAt: Instant,
    ): List<OnchainTransfer> =
        response.mapIndexedNotNull { index, reward ->
            if (reward.isNull || index >= accounts.size) return@mapIndexedNotNull null
            val account = accounts[index]
            val epoch = reward.path("epoch").asLong()
            val effectiveSlot = reward.path("effectiveSlot").asLong()
            OnchainTransfer(
                externalId = "solana:$epoch:$account",
                // onchain_transfer.signature is NOT NULL; rewards have none, so the column
                // carries the reward's natural key instead.
                signature = "reward:$epoch:$account",
                slot = effectiveSlot,
                blockHash = null,
                blockTime = blockTimeOf(effectiveSlot)?.let(Instant::ofEpochSecond) ?: observedAt,
                wallet = wallet,
                counterparty = null,
                tokenAccount = account,
                mintAddress = null,
                amountRaw = reward.path("amount").asText().toBigIntegerOrNull() ?: return@mapIndexedNotNull null,
                decimals = SOL_DECIMALS,
                direction = TransferDirection.IN,
                transferKind = TransferKind.STAKING_REWARD,
            )
        }

    private companion object {
        const val SOL_DECIMALS = 9
    }
}
