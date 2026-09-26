package com.mesta.asset.ingestion.marketdata

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** source_system written on every observation this pipeline produces. */
const val MARKETDATA_SOURCE_SYSTEM = "alphavantage"

/** The Alpha Vantage functions the adapter can pull; each maps to one daily-series shape. */
enum class MarketDataFunction {
    EQUITY_DAILY,
    FX_DAILY,
    CRYPTO_DAILY,
}

/**
 * One normalized market-data value — what every market-data adapter produces. Nothing outside a
 * vendor package may reference a provider payload shape (ADR-0001 vendor-boundary rule).
 * `field` must satisfy the `timeseries_observation_field_shape` CHECK (`^[a-z][a-zA-Z0-9_]*$`).
 */
data class MarketDataPoint(
    val field: String,
    val effectiveDate: LocalDate,
    val value: BigDecimal,
)

/**
 * One series the platform pulls into a registered `mesta.dataset`. [seriesKey] is the
 * platform's own naming — e.g. `equity:MSFT`, `fx:EURUSD`, `crypto:BTCUSD` — never a vendor
 * symbol. [quoteCurrency] is the FX to-currency / crypto market; unused for equities.
 */
data class MarketDataTarget(
    val datasetId: UUID,
    val seriesKey: String,
    val function: MarketDataFunction,
    val symbol: String,
    val quoteCurrency: String? = null,
) {
    init {
        require(seriesKey.isNotBlank()) { "seriesKey required" }
        require(symbol.isNotBlank()) { "symbol required" }
        if (function != MarketDataFunction.EQUITY_DAILY) {
            require(!quoteCurrency.isNullOrBlank()) { "quoteCurrency required for $function" }
        }
    }
}

/** Per-target outcome of one sync pass; a failed pull lands in [error] and stops nothing else. */
data class TargetSyncResult(
    val seriesKey: String,
    val pointsWritten: Int,
    val error: String? = null,
)
