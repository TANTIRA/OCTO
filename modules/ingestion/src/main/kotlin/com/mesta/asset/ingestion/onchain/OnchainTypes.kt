package com.mesta.asset.ingestion.onchain

import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/*
 * Vendor-neutral normalized types every onchain adapter produces. Nothing in this package may
 * reference a provider SDK shape: `helius/` adapters translate their payloads into these types
 * and the rest of the platform only ever sees them (ADR-0001 vendor-boundary rule).
 */

/** source_system written on every row this pipeline produces. */
const val ONCHAIN_SOURCE_SYSTEM = "helius-solana"

/** Only chain in v1; the column stays generic so a second chain needs no schema change. */
const val CHAIN_SOLANA = "solana"

/**
 * Direction of a transfer relative to the watched wallet. The `db` strings match the
 * `onchain_transfer_direction_known` CHECK in V10.
 */
enum class TransferDirection(
    val db: String,
) {
    IN("in"),
    OUT("out"),
    SELF("self"),
    FEE("fee"),
}

/**
 * Mirrors `instrument-flow-type @values` in the ontology and the V10 CHECK constraints. The
 * same string travels staging -> instrument_flow -> TypeDB, so it is never translated again.
 */
enum class TransferKind(
    val db: String,
) {
    TRANSFER_IN("transfer-in"),
    TRANSFER_OUT("transfer-out"),
    STAKING_REWARD("staking-reward"),
    AIRDROP("airdrop"),
    UNLOCK("unlock"),
    VESTING_CLAIM("vesting-claim"),
    MINT("mint"),
    BURN("burn"),
    OTHER("other"),
}

/**
 * One normalized onchain transfer leg. `externalId` is the deterministic identity
 * ("<chain>:<signature>:<account>:<instruction-path>") that dedupes poller replays and
 * webhook/poller overlap; it must never be derived from a provider-side id that can change.
 *
 * `amountRaw` is in base units; `decimals` is what the source reported at observation time.
 * `mintAddress == null` means native SOL.
 */
data class OnchainTransfer(
    val externalId: String,
    val signature: String,
    val slot: Long,
    val blockHash: String?,
    val blockTime: Instant,
    val wallet: String,
    val counterparty: String?,
    val tokenAccount: String?,
    val mintAddress: String?,
    val amountRaw: BigInteger,
    val decimals: Int,
    val direction: TransferDirection,
    val transferKind: TransferKind,
    val chain: String = CHAIN_SOLANA,
) {
    init {
        require(amountRaw.signum() >= 0) { "amount_raw must be non-negative" }
        require(decimals in 0..255) { "decimals out of u8 range: $decimals" }
    }
}

/** Observed wallet/token-account balance for reconciliation snapshots. */
data class OnchainBalance(
    val wallet: String,
    val tokenAccount: String?,
    val mintAddress: String?,
    val amountRaw: BigInteger,
    val decimals: Int,
    val usdValue: Double?,
    val source: BalanceSource,
    val slot: Long?,
    val asOf: Instant,
    val chain: String = CHAIN_SOLANA,
)

/** Where a balance observation came from; `db` matches the V10 source CHECK. */
enum class BalanceSource(
    val db: String,
) {
    WALLET_API("wallet-api"),
    DAS("das"),
    RPC("rpc"),
}

/** Normalized stake-account state for the staking-reward flow slice. */
data class StakeAccountInfo(
    val stakeAccount: String,
    val wallet: String,
    val voter: String?,
    val activationEpoch: Long?,
    val deactivationEpoch: Long?,
    val delegatedStakeRaw: BigInteger,
    val chain: String = CHAIN_SOLANA,
)

/** One address the platform watches, read back from `mesta.tracked_address`. */
data class WatchSource(
    val chain: String,
    val address: String,
    val tenantId: UUID?,
    val label: String?,
)


/** Evidence kinds the onchain adapter can produce — mirrors `onchain_claim_evidence.evidence_kind`. */
enum class EvidenceKind(
    val db: String,
) {
    TOKEN_SUPPLY("token-supply"),
    HOLDER_CONCENTRATION("holder-concentration"),
    TREASURY_BALANCE("treasury-balance"),
    ACCOUNT_ACTIVITY("account-activity"),
    OTHER("other"),
}

/**
 * One onchain observation backing an extracted claim (issue #116). The row is what the chain
 * reported — never the verdict; support/refute is a downstream decision point.
 *
 * [externalId] is the query's identity: "<chain>:<kind>:<subject>:<asOf>:<observed>" so an
 * identical re-observation dedupes while a changed value stages as a new fact.
 */
data class OnchainEvidence(
    val externalId: String,
    val claimRef: String,
    val subjectAddress: String,
    val kind: EvidenceKind,
    val observedNumeric: java.math.BigDecimal?,
    val observedText: String?,
    val payload: com.fasterxml.jackson.databind.JsonNode,
    val asOf: Instant,
    val chain: String = CHAIN_SOLANA,
)
