package com.mesta.asset.ingestion.marketdata.alphavantage

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AlphaVantageNormalizerTest {
    private val mapper = ObjectMapper()
    private val normalizer = AlphaVantageNormalizer()

    @Test
    fun `equity daily maps all five fields per date`() {
        val payload =
            mapper.readTree(
                """{"Time Series (Daily)":{
                    "2026-09-25":{"1. open":"100.0","2. high":"101.5","3. low":"99.0","4. close":"101.0","5. volume":"123456"},
                    "2026-09-24":{"1. open":"99.0","2. high":"100.0","3. low":"98.0","4. close":"99.5","5. volume":"100000"}}}""",
            )
        val points = normalizer.dailyEquity(payload)
        assertEquals(10, points.size)
        assertEquals(LocalDate.of(2026, 9, 24), points.first().effectiveDate)
        assertEquals(LocalDate.of(2026, 9, 25), points.last().effectiveDate)
        val close = points.single { it.effectiveDate == LocalDate.of(2026, 9, 25) && it.field == "close" }
        assertEquals(BigDecimal("101.0"), close.value)
    }

    @Test
    fun `fx daily has no volume field`() {
        val payload =
            mapper.readTree(
                """{"Time Series FX (Daily)":{
                    "2026-09-25":{"1. open":"1.17","2. high":"1.18","3. low":"1.16","4. close":"1.175"}}}""",
            )
        val points = normalizer.dailyFx(payload)
        assertEquals(4, points.size)
        assertEquals(setOf("open", "high", "low", "close"), points.map { it.field }.toSet())
    }

    @Test
    fun `crypto daily keeps the a-side quote, volume and market cap, and skips the b-side`() {
        val payload =
            mapper.readTree(
                """{"Time Series (Digital Currency Daily)":{
                    "2026-09-25":{"1a. open (USD)":"60000","1b. open (USD)":"60000","4a. close (USD)":"61000",
                                  "4b. close (USD)":"61000","5. volume":"321","6. market cap (USD)":"1200000"}}}""",
            )
        val points = normalizer.dailyCrypto(payload)
        assertEquals(4, points.size)
        assertEquals(setOf("open", "close", "volume", "marketCap"), points.map { it.field }.toSet())
        assertEquals(BigDecimal("61000"), points.single { it.field == "close" }.value)
    }

    @Test
    fun `unknown vendor keys are skipped and a missing series node fails loudly`() {
        val payload =
            mapper.readTree("""{"Time Series (Daily)":{"2026-09-25":{"1. open":"1.0","9. something":"x"}}}""")
        assertEquals(1, normalizer.dailyEquity(payload).size)

        assertFailsWith<IllegalArgumentException> { normalizer.dailyEquity(mapper.readTree("""{"Meta Data":{}}""")) }
    }
}
