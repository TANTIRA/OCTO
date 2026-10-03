package com.octo.ingestion.onchain.evm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.ingestion.http.HttpTransport
import com.octo.ingestion.http.RetryPolicy
import com.octo.ingestion.http.TransportResponse
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC POST client for an EVM endpoint (Arbitrum Nitro or a provider). Read-only:
 * only query methods exist; no `send*` method can ever appear here without a signing
 * design (arbitrum-ingestion-design.md §Provider).
 *
 * Retries follow [RetryPolicy]; every block-scoped call pins the `"finalized"` tag so a
 * staged fact can never be reorged away. Hex quantities are decoded at the edge — the
 * interface surface speaks plain Long/BigInteger.
 */
class EvmRpcClient(
    private val config: EvmConfig,
    private val transport: HttpTransport =
        HttpTransport { req ->
            val res =
                HttpClient
                    .newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build()
                    .send(req, HttpResponse.BodyHandlers.ofString())
            TransportResponse(res.statusCode(), res.headers().map(), res.body())
        },
    private val retry: RetryPolicy = RetryPolicy(),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it) },
    private val mapper: ObjectMapper = ObjectMapper(),
) : EvmRpcApi {
    private val ids = AtomicLong()

    override fun chainId(): Long =
        rpc("eth_chainId", mapper.createArrayNode())
            .asQuantityOrNull()
            ?.toLong()
            ?: throw EvmException("evm rpc eth_chainId returned a malformed quantity")

    override fun finalizedBlock(): JsonNode = rpc("eth_getBlockByNumber", mapper.createArrayNode().add("finalized").add(false))

    override fun blockByNumber(number: Long): JsonNode? {
        val result =
            rpc("eth_getBlockByNumber", mapper.createArrayNode().add(number.toHexQuantity()).add(false))
        return if (result.isNull) null else result
    }

    override fun transferLogs(
        fromBlock: Long,
        toBlock: Long,
        addresses: List<String>,
        fromSide: Boolean,
    ): JsonNode {
        val side = mapper.createArrayNode()
        addresses.forEach { side.add(it.asTopic()) }
        val topics = mapper.createArrayNode().add(TRANSFER_TOPIC)
        if (fromSide) {
            topics.add(side)
            topics.addNull()
        } else {
            topics.addNull()
            topics.add(side)
        }
        val filter =
            mapper
                .createObjectNode()
                .put("fromBlock", fromBlock.toHexQuantity())
                .put("toBlock", toBlock.toHexQuantity())
                .set<JsonNode>("topics", topics)
        return rpc("eth_getLogs", mapper.createArrayNode().add(filter))
    }

    override fun nativeBalance(address: String): BigInteger =
        rpc("eth_getBalance", mapper.createArrayNode().add(address).add("finalized"))
            .asQuantityOrNull()
            ?: throw EvmException("evm rpc eth_getBalance returned a malformed quantity for $address")

    override fun balanceOf(
        contract: String,
        address: String,
    ): BigInteger? {
        val data = BALANCE_OF_SELECTOR + address.asTopic().removePrefix("0x")
        return ethCall(contract, data)?.toQuantityOrNull()
    }

    override fun decimals(contract: String): Int? =
        ethCall(contract, DECIMALS_SELECTOR)
            ?.toQuantityOrNull()
            ?.let { runCatching { it.intValueExact() }.getOrNull() }
            ?.takeIf { it in 0..255 }

    override fun totalSupply(contract: String): BigInteger? = ethCall(contract, TOTAL_SUPPLY_SELECTOR)?.toQuantityOrNull()

    override fun transactionCount(address: String): Long =
        rpc("eth_getTransactionCount", mapper.createArrayNode().add(address).add("finalized"))
            .asQuantityOrNull()
            ?.toLong()
            ?: throw EvmException("evm rpc eth_getTransactionCount returned a malformed quantity for $address")

    /**
     * `eth_call` at `finalized`; returns the hex return-data string, or null when the contract
     * cannot answer: a revert, empty return data, or a deterministic VM failure (invalid opcode,
     * out of gas, invalid jump). Those last ones are properties of the bytecode, common in old
     * and hostile contracts, and reading them as a provider outage stalls the scan on that block
     * forever (#545). A transport error after retries, or a node error that is not the contract
     * (rate limit, "header not found", "missing trie node"), still propagates: that is an outage,
     * and treating it as "not a token" would skip the contract's transfers while the cursor
     * moves past them.
     */
    private fun ethCall(
        contract: String,
        data: String,
    ): String? {
        val call =
            mapper
                .createObjectNode()
                .put("to", contract)
                .put("data", data)
        val result =
            try {
                rpc("eth_call", mapper.createArrayNode().add(call).add("finalized"))
            } catch (e: EvmException) {
                if (e.isUnreadableContract()) return null
                throw e
            }
        return result.asText().takeIf { it != "0x" }
    }

    private fun rpc(
        method: String,
        params: JsonNode,
    ): JsonNode {
        val body = mapper.createObjectNode()
        body.put("jsonrpc", "2.0")
        body.put("id", ids.incrementAndGet())
        body.put("method", method)
        body.set<JsonNode>("params", params)
        val builder =
            HttpRequest
                .newBuilder(URI.create(config.rpcBaseUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
        config.apiKey?.let { builder.header("Authorization", "Bearer $it") }
        val request =
            builder
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()

        val response = sendWithRetry(request, method)
        val tree = mapper.readTree(response.body)
        val error = tree.path("error")
        if (!error.isMissingNode && !error.isNull) {
            throw EvmException(
                "evm rpc $method error: ${error.path("message").asText(error.toString())}",
                rpcCode = error.path("code").takeIf { it.isInt }?.asInt(),
            )
        }
        return tree.path("result")
    }

    private fun sendWithRetry(
        request: HttpRequest,
        method: String,
    ): TransportResponse {
        var attempt = 1
        while (true) {
            val response =
                try {
                    transport.send(request)
                } catch (e: java.io.IOException) {
                    if (attempt >= retry.maxAttempts) throw e
                    sleeper(retry.delayFor(attempt, null))
                    attempt++
                    continue
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw EvmException("evm rpc $method interrupted").apply { initCause(e) }
                }
            if (response.status in 200..299) return response
            if (!retry.shouldRetry(response.status, attempt)) {
                throw EvmException("evm rpc $method http ${response.status}", response.status)
            }
            sleeper(retry.delayFor(attempt, retryAfter(response)))
            attempt++
        }
    }

    private fun retryAfter(response: TransportResponse): Duration? =
        response.headers["Retry-After"]?.firstOrNull()?.let { runCatching { Duration.ofSeconds(it.toLong()) }.getOrNull() }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(15)

        /** `keccak256("Transfer(address,address,uint256)")` — ERC-20 log topic 0. */
        const val TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

        // ABI function selectors for the eth_call reads the adapter needs.
        const val DECIMALS_SELECTOR = "0x313ce567"
        const val BALANCE_OF_SELECTOR = "0x70a08231"
        const val TOTAL_SUPPLY_SELECTOR = "0x18160ddd"
    }
}

/** `0x` + 24 zero-pad + address — the 32-byte form a log topic carries. */
internal fun String.asTopic(): String = "0x" + "0".repeat(24) + lowercase().removePrefix("0x")

internal fun Long.toHexQuantity(): String = "0x" + toString(16)

internal fun JsonNode.asQuantity(): BigInteger = BigInteger(asText().removePrefix("0x"), 16)

/**
 * Quantity or null — malformed/missing hex never throws and never becomes zero: a field that
 * cannot be read is a record the caller must drop (fail closed), not a fabricated value.
 */
internal fun JsonNode.asQuantityOrNull(): BigInteger? = runCatching { asQuantity() }.getOrNull()

/** Return-data hex (possibly shorter than 32 bytes after leading-zero trim) -> quantity. */
internal fun String.toQuantity(): BigInteger = BigInteger(removePrefix("0x"), 16)

internal fun String.toQuantityOrNull(): BigInteger? = runCatching { toQuantity() }.getOrNull()

/**
 * The contract itself cannot answer this call. A revert (geth code 3, or "execution reverted"),
 * or a deterministic VM error the node does not label as a revert: invalid opcode, out of gas,
 * invalid jump. An HTTP failure never qualifies, nor does a provider error such as a rate limit
 * or a missing trie node — those must be retried, not skipped (#545).
 */
internal fun EvmException.isUnreadableContract(): Boolean {
    if (status != null) return false
    if (rpcCode == 3) return true
    val msg = message.orEmpty()
    if (msg.contains("revert", ignoreCase = true)) return true
    return UNREADABLE_CONTRACT_FAILURES.any { msg.contains(it, ignoreCase = true) }
}

private val UNREADABLE_CONTRACT_FAILURES = listOf("invalid opcode", "out of gas", "invalid jump")
