package com.mesta.asset.ingestion.marketdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mesta.asset.ingestion.marketdata.alphavantage.AlphaVantageApi
import com.mesta.asset.ingestion.marketdata.alphavantage.AlphaVantageException
import com.mesta.asset.ingestion.marketdata.alphavantage.AlphaVantageNormalizer
import com.mesta.asset.ingestion.persistence.Observation
import com.mesta.asset.ingestion.persistence.ObservationProvenance
import com.mesta.asset.ingestion.persistence.TimeSeriesWriter
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val DATASET: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d5")

/** Queued vendor stub: each call records its routing label and returns the next payload/exception. */
private class StubApi(
    vararg results: Any,
) : AlphaVantageApi {
    val calls = mutableListOf<String>()
    private val queue = ArrayDeque(results.toList())

    private fun next(label: String): JsonNode {
        calls += label
        return when (val result = queue.removeFirst()) {
            is JsonNode -> result
            is RuntimeException -> throw result
            else -> error("stub queue held $result")
        }
    }

    override fun dailyEquity(symbol: String): JsonNode = next("equity:$symbol")

    override fun dailyFx(
        fromSymbol: String,
        toSymbol: String,
    ): JsonNode = next("fx:$fromSymbol/$toSymbol")

    override fun dailyCrypto(
        symbol: String,
        market: String,
    ): JsonNode = next("crypto:$symbol/$market")
}

private class RecordingWriter : TimeSeriesWriter {
    val batches = mutableListOf<Pair<List<Observation>, ObservationProvenance>>()

    override fun write(
        observations: List<Observation>,
        provenance: ObservationProvenance,
    ): List<Observation> {
        batches += observations to provenance
        return observations
    }
}

class MarketDataSyncServiceTest {
    private val mapper = ObjectMapper()

    private val equityPayload =
        """{"Time Series (Daily)":{"2026-09-25":{"4. close":"101.0"}}}"""

    private fun target(
        function: MarketDataFunction,
        symbol: String = "IBM",
        quoteCurrency: String? = null,
    ) = MarketDataTarget(
        datasetId = DATASET,
        seriesKey = "series:$symbol",
        function = function,
        symbol = symbol,
        quoteCurrency = quoteCurrency,
    )

    @Test
    fun `a sync writes normalized points into the target dataset with alphavantage provenance`() {
        val api = StubApi(mapper.readTree(equityPayload))
        val writer = RecordingWriter()
        val result = MarketDataSyncService(api, AlphaVantageNormalizer(), writer).sync(target(MarketDataFunction.EQUITY_DAILY))

        assertEquals(TargetSyncResult("series:IBM", 1), result)
        val (observations, provenance) = writer.batches.single()
        assertEquals(
            Observation(DATASET, "series:IBM", "close", LocalDate.of(2026, 9, 25), BigDecimal("101.0")),
            observations.single(),
        )
        assertEquals(MARKETDATA_SOURCE_SYSTEM, provenance.sourceSystem)
        assertEquals("alphavantage-poller", provenance.actor)
    }

    @Test
    fun `fx and crypto targets pass their quote currency to the matching function`() {
        val api =
            StubApi(
                mapper.readTree("""{"Time Series FX (Daily)":{"2026-09-25":{"4. close":"1.17"}}}"""),
                mapper.readTree("""{"Time Series (Digital Currency Daily)":{"2026-09-25":{"4a. close (USD)":"61000"}}}"""),
            )
        val results =
            MarketDataSyncService(api, AlphaVantageNormalizer(), RecordingWriter()).syncAll(
                listOf(
                    target(MarketDataFunction.FX_DAILY, "EUR", "USD"),
                    target(MarketDataFunction.CRYPTO_DAILY, "BTC", "USD"),
                ),
            )

        assertEquals(listOf("fx:EUR/USD", "crypto:BTC/USD"), api.calls)
        assertTrue(results.all { it.error == null && it.pointsWritten == 1 })
    }

    @Test
    fun `a failing target records its error and the pass continues`() {
        val api =
            StubApi(
                AlphaVantageException("alphavantage TIME_SERIES_DAILY: rate limited"),
                mapper.readTree(equityPayload),
            )
        val results =
            MarketDataSyncService(api, AlphaVantageNormalizer(), RecordingWriter()).syncAll(
                listOf(target(MarketDataFunction.EQUITY_DAILY, "BAD"), target(MarketDataFunction.EQUITY_DAILY)),
            )

        assertTrue(results[0].error!!.contains("rate limited"))
        assertEquals(0, results[0].pointsWritten)
        assertNull(results[1].error)
        assertEquals(1, results[1].pointsWritten)
    }

    @Test
    fun `a malformed payload fails the target instead of writing partial rows`() {
        val api = StubApi(mapper.readTree("""{"Meta Data":{}}"""))
        val result =
            MarketDataSyncService(api, AlphaVantageNormalizer(), RecordingWriter()).sync(target(MarketDataFunction.EQUITY_DAILY))

        assertTrue(result.error!!.contains("Time Series (Daily)"))
        assertEquals(0, result.pointsWritten)
    }
}
