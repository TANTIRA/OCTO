package com.mesta.asset.ingestion.marketdata.alphavantage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.http.HttpTransport
import com.mesta.asset.ingestion.http.RetryPolicy
import com.mesta.asset.ingestion.http.TransportResponse
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Non-retryable failure: an HTTP error after retries, or a 200 body carrying an Alpha Vantage
 * `Error Message`/`Note`/`Information` envelope — those mean a bad parameter, an exhausted
 * daily quota, or a premium-gated function, none of which a retry fixes. Messages never carry
 * the request URL: the `apikey` query parameter must not reach a log.
 */
class AlphaVantageException(
    message: String,
    val status: Int? = null,
) : RuntimeException(message)

/**
 * Alpha Vantage `GET /query` surface used by the market-data sync. Read-only daily series only
 * — intraday, options, and the intelligence endpoints are out of scope for the adapter slice
 * (docs/alphavantage-fit-assessment.md).
 */
interface AlphaVantageApi {
    /** `TIME_SERIES_DAILY` — one OHLCV record per trading day for an equity symbol. */
    fun dailyEquity(symbol: String): JsonNode

    /** `FX_DAILY` — daily OHLC for a currency pair, e.g. EUR→USD. */
    fun dailyFx(
        fromSymbol: String,
        toSymbol: String,
    ): JsonNode

    /** `DIGITAL_CURRENCY_DAILY` — daily OHLCV for a crypto symbol quoted in [market]. */
    fun dailyCrypto(
        symbol: String,
        market: String,
    ): JsonNode
}

/**
 * GET client for Alpha Vantage. `outputsize=compact` on every call: the adapter is a daily
 * batch job on a tight request budget, not a history backfill (a `full` pull is a separate
 * one-shot tool, not this loop).
 */
class AlphaVantageClient(
    private val config: AlphaVantageConfig,
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
) : AlphaVantageApi {
    override fun dailyEquity(symbol: String): JsonNode =
        query(mapOf("function" to "TIME_SERIES_DAILY", "symbol" to symbol))

    override fun dailyFx(
        fromSymbol: String,
        toSymbol: String,
    ): JsonNode = query(mapOf("function" to "FX_DAILY", "from_symbol" to fromSymbol, "to_symbol" to toSymbol))

    override fun dailyCrypto(
        symbol: String,
        market: String,
    ): JsonNode = query(mapOf("function" to "DIGITAL_CURRENCY_DAILY", "symbol" to symbol, "market" to market))

    private fun query(params: Map<String, String>): JsonNode {
        val function = params.getValue("function")
        val queryString =
            (params + mapOf("outputsize" to "compact", "datatype" to "json", "apikey" to config.apiKey))
                .entries
                .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
        val request =
            HttpRequest
                .newBuilder(URI.create("${config.baseUrl}/query?$queryString"))
                .timeout(TIMEOUT)
                .GET()
                .build()

        val tree = mapper.readTree(sendWithRetry(request, function).body)
        for (envelope in listOf("Error Message", "Note", "Information")) {
            tree.path(envelope).takeIf { it.isTextual }?.let {
                throw AlphaVantageException("alphavantage $function: ${it.asText()}")
            }
        }
        return tree
    }

    private fun sendWithRetry(
        request: HttpRequest,
        function: String,
    ): TransportResponse {
        var attempt = 1
        while (true) {
            val response =
                try {
                    transport.send(request)
                } catch (e: IOException) {
                    if (attempt >= retry.maxAttempts) throw AlphaVantageException("alphavantage $function: ${e.message}")
                    sleeper(retry.delayFor(attempt, null))
                    attempt++
                    continue
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw AlphaVantageException("alphavantage $function interrupted").apply { initCause(e) }
                }
            if (response.status in 200..299) return response
            if (!retry.shouldRetry(response.status, attempt)) {
                throw AlphaVantageException("alphavantage $function http ${response.status}", response.status)
            }
            sleeper(retry.delayFor(attempt, retryAfter(response)))
            attempt++
        }
    }

    private fun retryAfter(response: TransportResponse): Duration? =
        response.headers["Retry-After"]?.firstOrNull()?.let { Duration.ofSeconds(it.toLong()) }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
