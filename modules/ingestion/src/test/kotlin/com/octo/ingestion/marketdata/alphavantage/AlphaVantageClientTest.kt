package com.mesta.asset.ingestion.marketdata.alphavantage

import com.mesta.asset.ingestion.http.FakeTransport
import com.mesta.asset.ingestion.http.HttpTransport
import com.mesta.asset.ingestion.http.okJson
import com.mesta.asset.ingestion.http.statusOf
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val config = AlphaVantageConfig(apiKey = "test-key")

private val SERIES = """{"Meta Data":{"2. Symbol":"IBM"},"Time Series (Daily)":{"2026-09-25":{"1. open":"1.0"}}}"""

class AlphaVantageClientTest {
    private val sleeps = mutableListOf<Duration>()

    private fun client(transport: HttpTransport): AlphaVantageClient =
        AlphaVantageClient(config, transport = transport, sleeper = { sleeps += it })

    @Test
    fun `dailyEquity hits the query endpoint with function, symbol and key`() {
        val transport = FakeTransport(okJson(SERIES))
        val result = client(transport).dailyEquity("IBM")

        assertTrue(result.path("Time Series (Daily)").isObject)
        val uri =
            transport.requests
                .single()
                .uri()
                .toString()
        assertTrue(uri.startsWith("https://www.alphavantage.co/query?"))
        for (param in listOf("function=TIME_SERIES_DAILY", "symbol=IBM", "apikey=test-key", "outputsize=compact")) {
            assertTrue(uri.contains(param), "uri must contain $param")
        }
    }

    @Test
    fun `dailyFx and dailyCrypto send their pair and market parameters`() {
        val transport = FakeTransport(okJson("{}"), okJson("{}"))
        client(transport).dailyFx("EUR", "USD")
        client(transport).dailyCrypto("BTC", "USD")

        val fxUri = transport.requests[0].uri().toString()
        assertTrue(fxUri.contains("function=FX_DAILY"))
        assertTrue(fxUri.contains("from_symbol=EUR"))
        assertTrue(fxUri.contains("to_symbol=USD"))
        val cryptoUri = transport.requests[1].uri().toString()
        assertTrue(cryptoUri.contains("function=DIGITAL_CURRENCY_DAILY"))
        assertTrue(cryptoUri.contains("market=USD"))
    }

    @Test
    fun `a 200 error envelope throws - the status code never tells you`() {
        for (envelope in listOf("Error Message", "Note", "Information")) {
            val transport = FakeTransport(okJson("""{"$envelope":"denied"}"""))
            val e = assertFailsWith<AlphaVantageException> { client(transport).dailyEquity("IBM") }
            assertTrue(e.message!!.contains("denied"), "$envelope must surface its text")
        }
    }

    @Test
    fun `429 retries with backoff, other 4xx fail at once, 5xx exhausts attempts`() {
        val flaky = FakeTransport(statusOf(429, retryAfterSeconds = 3), okJson(SERIES))
        client(flaky).dailyEquity("IBM")
        assertEquals(listOf(Duration.ofSeconds(3)), sleeps)

        assertFailsWith<AlphaVantageException> { client(FakeTransport(statusOf(400))).dailyEquity("IBM") }

        val e =
            assertFailsWith<AlphaVantageException> {
                client(FakeTransport(statusOf(500), statusOf(500), statusOf(500))).dailyEquity("IBM")
            }
        assertEquals(500, e.status)
    }

    @Test
    fun `failures never leak the api key into the exception`() {
        val transport = FakeTransport(statusOf(403))
        val e = assertFailsWith<AlphaVantageException> { client(transport).dailyEquity("IBM") }
        assertFalse(e.message!!.contains("test-key"), "the key lives in the URL - errors must not carry it")
    }
}
