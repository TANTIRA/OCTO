package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
        return rpc("getSignaturesForAddress", mapper.createArrayNode().add(address).add(params))
    }

    override fun transaction(signature: String): JsonNode? {
        val params =
            mapper
                .createObjectNode()
                .put("encoding", "jsonParsed")
                .put("maxSupportedTransactionVersion", 0)
                .put("commitment", "finalized")
        val result = rpc("getTransaction", mapper.createArrayNode().add(signature).add(params))
        return if (result.isNull) null else result
    }

    override fun balance(address: String): Long {
        val params = mapper.createObjectNode().put("commitment", "finalized")
        val result = rpc("getBalance", mapper.createArrayNode().add(address).add(params))
        return result.path("value").asLong()
    }

    override fun tokenAccountsByOwner(address: String): JsonNode {
        val owner = mapper.createObjectNode().put("programId", SPL_TOKEN_PROGRAM_ID)
        val cfg = mapper.createObjectNode().put("encoding", "jsonParsed").put("commitment", "finalized")
        return rpc(
            "getTokenAccountsByOwner",
            mapper
                .createArrayNode()
                .add(address)
                .add(owner)
                .add(cfg),
        )
    }

    private fun rpc(
        method: String,
        params: JsonNode,
    ): JsonNode {
        val body =
            mapper
                .createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", ids.incrementAndGet())
                .put("method", method)
                .set<JsonNode>("params", params)
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
    }
}
