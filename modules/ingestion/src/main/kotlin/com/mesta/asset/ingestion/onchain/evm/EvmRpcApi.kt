package com.mesta.asset.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger

/** Non-retryable failure or a JSON-RPC `error` object. `status` is null for RPC-level errors. */
class EvmException(
    message: String,
    val status: Int? = null,
) : RuntimeException(message)

/**
 * EVM JSON-RPC surface for the Arbitrum poller. Read-only by construction — no `send*`
 * method can appear here without a signing design (the same exclusion the Helius adapter
 * documents). Every block-scoped call pins `"finalized"`: on Arbitrum that tag means the
 * transaction's batch is posted to Ethereum and its L1 block is final, which is the only
 * data that cannot roll back — the same staging posture as Solana's `commitment` gate.
 */
interface EvmRpcApi {
    /** `eth_chainId` — sanity-checked against the configured chain before a scan runs. */
    fun chainId(): Long

    /** `eth_getBlockByNumber("finalized")` — the scan head: `number`, `hash`, `timestamp`. */
    fun finalizedBlock(): JsonNode

    /** `eth_getBlockByNumber` for a concrete number; null when the block is unknown. */
    fun blockByNumber(number: Long): JsonNode?

    /**
     * `eth_getLogs` for ERC-20 `Transfer` events touching [addresses] on one side of the
     * transfer. [fromSide] = true filters `topics[1]` (senders); false filters `topics[2]`
     * (recipients). Both queries are needed — a single filter cannot express "either side".
     */
    fun transferLogs(
        fromBlock: Long,
        toBlock: Long,
        addresses: List<String>,
        fromSide: Boolean,
    ): JsonNode

    /** `eth_getBalance` at `finalized` — wei. */
    fun nativeBalance(address: String): BigInteger

    /** `balanceOf(address)` `eth_call` on [contract] at `finalized`; null when the call reverts or returns empty. */
    fun balanceOf(
        contract: String,
        address: String,
    ): BigInteger?

    /** `decimals()` `eth_call`; null when the contract does not implement it — never assumed. */
    fun decimals(contract: String): Int?

    /** `totalSupply()` `eth_call`; null when unimplemented. */
    fun totalSupply(contract: String): BigInteger?

    /** `eth_getTransactionCount` at `finalized` — the account-activity signal. */
    fun transactionCount(address: String): Long
}
