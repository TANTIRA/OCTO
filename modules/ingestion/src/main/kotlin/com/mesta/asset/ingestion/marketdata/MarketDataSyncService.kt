package com.mesta.asset.ingestion.marketdata

import com.mesta.asset.ingestion.marketdata.alphavantage.AlphaVantageApi
import com.mesta.asset.ingestion.marketdata.alphavantage.AlphaVantageNormalizer
import com.mesta.asset.ingestion.persistence.Observation
import com.mesta.asset.ingestion.persistence.ObservationProvenance
import com.mesta.asset.ingestion.persistence.TimeSeriesWriter
import java.util.UUID

/**
 * Pulls each target's daily series, normalizes it, and appends the points to its registered
 * `mesta.dataset` (V12). Re-runs are safe: the store is append-only and bi-temporal, so a repeat
 * observation supersedes nothing and the `asOfTime` read still resolves to the newest record —
 * the only cost of a replay is a redundant row.
 *
 * A failed target records its error and the pass continues — with a request budget this thin,
 * one bad symbol must not starve the rest of the batch.
 */
class MarketDataSyncService(
    private val api: AlphaVantageApi,
    private val normalizer: AlphaVantageNormalizer,
    private val writer: TimeSeriesWriter,
    private val actor: String = "alphavantage-poller",
) {
    fun syncAll(targets: List<MarketDataTarget>): List<TargetSyncResult> = targets.map(::sync)

    fun sync(target: MarketDataTarget): TargetSyncResult =
        try {
            val points =
                when (target.function) {
                    MarketDataFunction.EQUITY_DAILY -> normalizer.dailyEquity(api.dailyEquity(target.symbol))
                    MarketDataFunction.FX_DAILY -> normalizer.dailyFx(api.dailyFx(target.symbol, target.quoteCurrency!!))
                    MarketDataFunction.CRYPTO_DAILY -> normalizer.dailyCrypto(api.dailyCrypto(target.symbol, target.quoteCurrency!!))
                }
            if (points.isEmpty()) {
                TargetSyncResult(target.seriesKey, 0)
            } else {
                val observations =
                    points.map { Observation(target.datasetId, target.seriesKey, it.field, it.effectiveDate, it.value) }
                val written = writer.write(observations, provenance())
                TargetSyncResult(target.seriesKey, written.size)
            }
        } catch (e: RuntimeException) {
            TargetSyncResult(target.seriesKey, 0, e.message)
        }

    private fun provenance() =
        ObservationProvenance(MARKETDATA_SOURCE_SYSTEM, actor, UUID.randomUUID(), UUID.randomUUID())
}
