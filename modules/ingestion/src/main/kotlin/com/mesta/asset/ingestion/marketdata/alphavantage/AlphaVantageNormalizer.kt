package com.mesta.asset.ingestion.marketdata.alphavantage

import com.fasterxml.jackson.databind.JsonNode
import com.mesta.asset.ingestion.marketdata.MarketDataPoint
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps Alpha Vantage daily-series payloads onto vendor-neutral [MarketDataPoint]s. Vendor key
 * shapes — `"Time Series FX (Daily)"`, `"4a. close (USD)"` — never leave this class (ADR-0001).
 */
class AlphaVantageNormalizer {
    fun dailyEquity(payload: JsonNode): List<MarketDataPoint> = dailySeries(payload, "Time Series (Daily)", EQUITY_FIELDS)

    fun dailyFx(payload: JsonNode): List<MarketDataPoint> = dailySeries(payload, "Time Series FX (Daily)", FX_FIELDS)

    fun dailyCrypto(payload: JsonNode): List<MarketDataPoint> = dailySeries(payload, "Time Series (Digital Currency Daily)", CRYPTO_FIELDS)

    private fun dailySeries(
        payload: JsonNode,
        seriesNode: String,
        fields: Map<String, String>,
    ): List<MarketDataPoint> {
        val series = payload.path(seriesNode)
        require(series.isObject) { "payload has no '$seriesNode' node" }
        val points = mutableListOf<MarketDataPoint>()
        for ((date, record) in series.properties()) {
            for ((rawKey, value) in record.properties()) {
                val field = canonicalField(rawKey, fields) ?: continue
                points += MarketDataPoint(field, LocalDate.parse(date), BigDecimal(value.asText()))
            }
        }
        return points.sortedWith(compareBy({ it.effectiveDate }, { it.field }))
    }

    /**
     * `"4a. close (USD)"` → field `close`; null when the key is unknown or belongs to the
     * vendor's secondary series — `b`-suffixed keys re-quote the same bar in USD and would
     * collide with the `a`-side field.
     */
    private fun canonicalField(
        rawKey: String,
        fields: Map<String, String>,
    ): String? {
        val match = FIELD_KEY.matchEntire(rawKey) ?: return null
        if (match.groupValues[2].isNotEmpty() && match.groupValues[2] != "a") return null
        return fields[match.groupValues[3].lowercase()]
    }

    companion object {
        private val FIELD_KEY = Regex("""^(\d+)([a-z])?\. ([^()]+?)( \([^)]*\))?$""")

        private val OHLC =
            mapOf("open" to "open", "high" to "high", "low" to "low", "close" to "close")
        private val EQUITY_FIELDS = OHLC + ("volume" to "volume")
        private val FX_FIELDS = OHLC
        private val CRYPTO_FIELDS = EQUITY_FIELDS + ("market cap" to "marketCap")
    }
}
