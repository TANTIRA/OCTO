package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Minimal response shape the clients need — keeps tests free of JDK HttpResponse stubs. */
data class TransportResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
)

/**
 * The HTTP seam the clients talk through. Tests inject a fake transport so no call ever leaves
 * the process; production wires `java.net.http.HttpClient::send`.
 */
fun interface HttpTransport {
    fun send(request: HttpRequest): TransportResponse
}

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

    /** `getBalance` — native SOL lamports. */
    fun balance(address: String): Long

    /** `getTokenAccountsByOwner` under the SPL Token program, parsed. */
    fun tokenAccountsByOwner(address: String): JsonNode
}

/**
 * Helius Wallet API (`/v1/wallet/{address}/...`) — aggregated balances/history used for
 * reconciliation snapshots and fast history backfill.
 */
interface HeliusWalletApi {
    fun balances(address: String): JsonNode

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
