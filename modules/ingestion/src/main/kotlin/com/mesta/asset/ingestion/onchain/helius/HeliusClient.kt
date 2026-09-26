package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode

/** Non-retryable failure or a JSON-RPC `error` object. `status` is null for RPC-level errors. */
class HeliusException(
    message: String,
    val status: Int? = null,
) : RuntimeException(message)

/**
 * Solana JSON-RPC surface used by the poller. Every method pins `commitment: "finalized"` —
 * the only level that cannot roll back, so staged facts never need reorg handling.
 */
interface HeliusRpcApi {
    /**
     * `getSignaturesForAddress`, newest-first. `before` pages backward; `until` stops the scan at
     * a known signature (exclusive) — the incremental-sync cursor.
     */
    fun signaturesForAddress(
        address: String,
        limit: Int = 100,
        before: String? = null,
        until: String? = null,
    ): JsonNode

    /** `getTransaction` with `jsonParsed` encoding; null when the signature is unknown. */
    fun transaction(signature: String): JsonNode?

    /**
     * `getTransactionsForAddress` (Helius extension), newest-first, `transactionDetails: "full"`,
     * `filters.tokenAccounts: "balanceChanged"` — every finalized transaction where the wallet
     * OR one of its token accounts changed balance. `getSignaturesForAddress` only sees the
     * wallet itself, so an SPL transfer that touches only the wallet's ATA is invisible to it;
     * the `balanceChanged` filter closes that coverage gap server-side.
     *
     * [slotGt] is the incremental cursor — only slots above the newest staged slot return —
     * and `paginationToken` ("slot:position") pages backward, so two transactions in the same
     * slot cannot hide each other the way a signature `until` cursor can. Returns the raw
     * `result` node: `data` holds `getTransaction`-shaped objects, `paginationToken` is absent
     * when the scan is exhausted.
     */
    fun transactionsForAddress(
        address: String,
        limit: Int = 100,
        paginationToken: String? = null,
        slotGt: Long? = null,
    ): JsonNode

    /** `getBalance` — native SOL lamports. */
    fun balance(address: String): Long

    /** `getTokenAccountsByOwner` under the SPL Token program, parsed. */
    fun tokenAccountsByOwner(address: String): JsonNode

    /**
     * `getProgramAccounts` on the stake program, `jsonParsed`, filtered to accounts whose
     * `Authorized` names [address] as staker (offset 44) or withdrawer (offset 76) — the RPC
     * ANDs memcmp filters, so the two authorities need separate calls merged here.
     */
    fun stakeAccounts(address: String): JsonNode

    /**
     * `getInflationReward` — one result slot per queried address in request order; a null
     * entry means that account earned nothing in the epoch. Null [epoch] asks for the most
     * recent one.
     */
    fun inflationReward(
        addresses: List<String>,
        epoch: Long? = null,
    ): JsonNode

    /** `getBlockTime` — historical estimate for a produced slot; null when unknown. */
    fun blockTime(slot: Long): Long?

    /** `getTokenSupply` — total supply for a mint (`amount`, `decimals`, `uiAmount`). */
    fun tokenSupply(mint: String): JsonNode

    /** `getTokenLargestAccounts` — the up-to-20 largest token accounts of a mint. */
    fun tokenLargestAccounts(mint: String): JsonNode

    /**
     * `getSignatureStatuses` for a batch of signatures, `searchTransactionHistory: false`.
     * Deliberately the one method without a commitment pin: the point is the node's *current*
     * confirmation view — the webhook path checks a delivered transaction really reached
     * `finalized` before staging it (#167). `result.value` aligns with request order; a null
     * entry means the signature is unknown to the node.
     */
    fun signatureStatuses(signatures: List<String>): JsonNode
}

/**
 * Helius Wallet API (`/v1/wallet/{address}/...`) — aggregated balances/history used for
 * reconciliation snapshots and fast history backfill.
 */
interface HeliusWalletApi {
    /** `/balances` — paginated manually; `pagination.hasMore` in the response drives [page]. */
    fun balances(
        address: String,
        page: Int = 1,
    ): JsonNode

    fun transfers(
        address: String,
        limit: Int = 100,
        before: String? = null,
    ): JsonNode

    fun history(
        address: String,
        limit: Int = 100,
        before: String? = null,
    ): JsonNode
}
