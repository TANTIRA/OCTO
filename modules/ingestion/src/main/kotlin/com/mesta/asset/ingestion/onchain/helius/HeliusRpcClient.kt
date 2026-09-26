package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.http.HttpTransport
import com.mesta.asset.ingestion.http.RetryPolicy
import com.mesta.asset.ingestion.http.TransportResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC POST client for Helius Solana endpoints. Read-only: only query methods exist; no
 * `send*` method can ever appear here without a signing design (helius-fit-assessment.md).
 *
 * Retries follow [RetryPolicy]; the request body carries `commitment: "finalized"` on every
 * method that accepts a commitment config.
 */
class HeliusRpcClient(
    private val config: HeliusConfig,
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
) : HeliusRpcApi {
    private val ids = AtomicLong()

    override fun signaturesForAddress(
        address: String,
        limit: Int,
        before: String?,
        until: String?,
    ): JsonNode {
        val params = mapper.createObjectNode()
        params.put("limit", limit)
        params.put("commitment", "finalized")
        if (before != null) params.put("before", before)
        if (until != null) params.put("until", until)
        return rpc(
            "getSignaturesForAddress",
            mapper
                .createArrayNode()
                .add(address)
                .add(params),
        )
    }

    override fun transaction(signature: String): JsonNode? {
        val params =
            mapper
                .createObjectNode()
                .put("encoding", "jsonParsed")
                .put("maxSupportedTransactionVersion", 0)
                .put("commitment", "finalized")
        val args =
            mapper
                .createArrayNode()
                .add(signature)
                .add(params)
        val result = rpc("getTransaction", args)
        return if (result.isNull) null else result
    }

    override fun balance(address: String): Long {
        val params = mapper.createObjectNode().put("commitment", "finalized")
        val args =
            mapper
                .createArrayNode()
                .add(address)
                .add(params)
        return rpc("getBalance", args).path("value").asLong()
    }

    override fun tokenAccountsByOwner(address: String): JsonNode {
        val owner = mapper.createObjectNode().put("programId", SPL_TOKEN_PROGRAM_ID)
        val cfg =
            mapper
                .createObjectNode()
                .put("encoding", "jsonParsed")
                .put("commitment", "finalized")
        val args =
            mapper
                .createArrayNode()
                .add(address)
                .add(owner)
                .add(cfg)
        return rpc("getTokenAccountsByOwner", args)
    }

    override fun stakeAccounts(address: String): JsonNode {
        val merged = mapper.createArrayNode()
        val seen = mutableSetOf<String>()
        for (offset in AUTHORIZED_OFFSETS) {
            val memcmp =
                mapper
                    .createObjectNode()
                    .put("offset", offset)
                    .put("bytes", address)
                    .put("encoding", "base58")
            val filter = mapper.createObjectNode().set<JsonNode>("memcmp", memcmp)
            val cfg =
                mapper
                    .createObjectNode()
                    .put("encoding", "jsonParsed")
                    .put("commitment", "finalized")
                    .set<JsonNode>("filters", mapper.createArrayNode().add(filter))
            rpc("getProgramAccounts", mapper.createArrayNode().add(STAKE_PROGRAM_ID).add(cfg))
                .forEach { account ->
                    if (seen.add(account.path("pubkey").asText())) merged.add(account)
                }
        }
        return merged
    }

    override fun inflationReward(
        addresses: List<String>,
        epoch: Long?,
    ): JsonNode {
        if (addresses.isEmpty()) return mapper.createArrayNode()
        val cfg = mapper.createObjectNode().put("commitment", "finalized")
        epoch?.let { cfg.put("epoch", it) }
        val params = mapper.createArrayNode()
        addresses.forEach { params.add(it) }
        return rpc("getInflationReward", params.add(cfg))
    }

    override fun blockTime(slot: Long): Long? {
        val result =
            try {
                rpc("getBlockTime", mapper.createArrayNode().add(slot))
            } catch (e: HeliusException) {
                return null // unknown or purged slot — the caller falls back to observation time
            }
        return if (result.isNumber) result.asLong() else null
    }

    override fun tokenSupply(mint: String): JsonNode {
        val params = mapper.createObjectNode().put("commitment", "finalized")
        return rpc("getTokenSupply", mapper.createArrayNode().add(mint).add(params)).path("value")
    }

    override fun tokenLargestAccounts(mint: String): JsonNode {
        val params = mapper.createObjectNode().put("commitment", "finalized")
        return rpc("getTokenLargestAccounts", mapper.createArrayNode().add(mint).add(params)).path("value")
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
        val request =
            HttpRequest
                .newBuilder(URI.create("${config.rpcBaseUrl}/?api-key=${config.apiKey}"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()

        val response = sendWithRetry(request, method)
        val tree = mapper.readTree(response.body)
        val error = tree.path("error")
        if (!error.isMissingNode && !error.isNull) {
            throw HeliusException("helius rpc $method error: ${error.path("message").asText(error.toString())}")
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
                    throw HeliusException("helius rpc $method interrupted").apply { initCause(e) }
                }
            if (response.status in 200..299) return response
            if (!retry.shouldRetry(response.status, attempt)) {
                throw HeliusException("helius rpc $method http ${response.status}", response.status)
            }
            sleeper(retry.delayFor(attempt, retryAfter(response)))
            attempt++
        }
    }

    private fun retryAfter(response: TransportResponse): Duration? =
        response.headers["Retry-After"]?.firstOrNull()?.let { Duration.ofSeconds(it.toLong()) }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(15)
        const val SPL_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val STAKE_PROGRAM_ID = "Stake11111111111111111111111111111111111111"

        /** Byte offsets of `Authorized::staker` and `Authorized::withdrawer` in the stake layout. */
        val AUTHORIZED_OFFSETS = listOf(44, 76)
    }
}
