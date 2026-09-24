package com.mesta.asset.ingestion.onchain.helius

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Helius Wallet API client (`GET /v1/wallet/{address}/...`). Read-only; the same retry rules as
 * [HeliusRpcClient]. Responses are raw JSON — normalization happens in the adapter layer.
 */
class HeliusWalletClient(
    private val config: HeliusConfig,
    private val transport: HttpTransport =
        HttpTransport { req ->
            val res =
                HttpClient.newBuilder().connectTimeout(HeliusRpcClient.TIMEOUT).build()
                    .send(req, HttpResponse.BodyHandlers.ofString())
            TransportResponse(res.statusCode(), res.headers().map(), res.body())
        },
    private val retry: RetryPolicy = RetryPolicy(),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it) },
    private val mapper: ObjectMapper = ObjectMapper(),
) : HeliusWalletApi {

    override fun balances(address: String): JsonNode = get("/v1/wallet/$address/balances", emptyMap())

    override fun transfers(
        address: String,
        limit: Int,
        before: String?,
    ): JsonNode = get("/v1/wallet/$address/transfers", pageParams(limit, before))

    override fun history(
        address: String,
        limit: Int,
        before: String?,
    ): JsonNode = get("/v1/wallet/$address/history", pageParams(limit, before))

    private fun pageParams(
        limit: Int,
        before: String?,
    ): Map<String, String> = buildMap {
        put("limit", limit.toString())
        if (before != null) put("before", before)
    }

    private fun get(
        path: String,
        params: Map<String, String>,
    ): JsonNode {
        val query =
            (params + ("api-key" to config.apiKey)).entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
            }
        val request =
            HttpRequest.newBuilder(URI.create("${config.walletApiBaseUrl}$path?$query"))
                .timeout(HeliusRpcClient.TIMEOUT)
                .GET()
                .build()

        var attempt = 1
        while (true) {
            val response = transport.send(request)
            if (response.status in 200..299) return mapper.readTree(response.body)
            if (!retry.shouldRetry(response.status, attempt)) {
                throw HeliusException("helius wallet GET $path http ${response.status}", response.status)
            }
            sleeper(retry.delayFor(attempt, retryAfter(response)))
            attempt++
        }
    }

    private fun retryAfter(response: TransportResponse): Duration? =
        response.headers["Retry-After"]?.firstOrNull()?.let { Duration.ofSeconds(it.toLong()) }
}
