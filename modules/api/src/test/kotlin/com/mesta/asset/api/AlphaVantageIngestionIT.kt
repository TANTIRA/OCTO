package com.mesta.asset.api

import com.mesta.asset.ingestion.http.HttpTransport
import com.mesta.asset.ingestion.http.TransportResponse
import com.mesta.asset.ingestion.marketdata.MARKETDATA_SOURCE_SYSTEM
import com.mesta.asset.ingestion.marketdata.MarketDataFunction
import com.mesta.asset.ingestion.marketdata.MarketDataSyncService
import com.mesta.asset.ingestion.marketdata.MarketDataTarget
import com.mesta.asset.ingestion.marketdata.alphavantage.AlphaVantageClient
import com.mesta.asset.ingestion.marketdata.alphavantage.AlphaVantageConfig
import com.mesta.asset.ingestion.marketdata.alphavantage.AlphaVantageNormalizer
import com.mesta.asset.ingestion.persistence.JdbcTimeSeriesStore
import com.mesta.asset.ingestion.persistence.TimeSeriesQuery
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end coverage of the Alpha Vantage adapter slice against the real V12 schema: a canned
 * vendor response travels client -> normalizer -> sync -> `timeseries_observation`, and the
 * bi-temporal read resolves a replayed pull. The transport is faked — no call leaves the
 * process, which is also why no API key is needed here. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class AlphaVantageIngestionIT {
    private val dataSource by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("mesta")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    private val store by lazy { JdbcTimeSeriesStore(dataSource) }

    private fun service(body: String) =
        MarketDataSyncService(
            AlphaVantageClient(
                AlphaVantageConfig(apiKey = "it-key"),
                transport = HttpTransport { TransportResponse(200, emptyMap(), body) },
            ),
            AlphaVantageNormalizer(),
            store,
        )

    private fun dataset(): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        with t as (insert into mesta.tenant (slug, display_name, source_system, correlation_id)
                                   values ('t-${UUID.randomUUID().toString().take(8)}', 'T', 'test', gen_random_uuid()) returning id)
                        insert into mesta.dataset (tenant_id, name, description, unit, currency_code, source_system, actor, correlation_id)
                        select id, 'mkt.equity.daily', 'Daily equity bars', 'usd/share', 'USD', 'test', 'it', gen_random_uuid() from t
                        returning id
                        """.trimIndent(),
                    ).use { rows ->
                        rows.next()
                        rows.getObject("id", UUID::class.java)
                    }
            }
        }

    private fun target(datasetId: UUID) =
        MarketDataTarget(datasetId, "equity:IBM", MarketDataFunction.EQUITY_DAILY, "IBM")

    private fun dailyPayload(close: String) =
        """{"Meta Data":{"2. Symbol":"IBM"},
           "Time Series (Daily)":{"2026-09-25":{"1. open":"100.0","2. high":"101.5","3. low":"99.0",
                                               "4. close":"$close","5. volume":"123456"}}}"""

    @Test
    fun `a sync lands every bar field in the dataset with alphavantage provenance`() {
        val dataset = dataset()
        val result = service(dailyPayload("101.0")).sync(target(dataset))

        assertThat(result.error).isNull()
        assertThat(result.pointsWritten).isEqualTo(5)

        val rows =
            store.query(TimeSeriesQuery(dataset, LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-26")))
        assertThat(rows.map { it.seriesKey }.toSet()).containsExactly("equity:IBM")
        assertThat(rows.map { it.field }.toSet()).containsExactlyInAnyOrder("open", "high", "low", "close", "volume")
        assertThat(rows.single { it.field == "close" }.value.toPlainString()).isEqualTo("101.0000000000")
        assertThat(rows.all { it.recordedAt != null }).isTrue()

        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "select source_system, actor from mesta.timeseries_observation " +
                        "where dataset_id = '$dataset' and field = 'close'",
                ).use { r ->
                    assertThat(r.next()).isTrue()
                    assertThat(r.getString(1)).isEqualTo(MARKETDATA_SOURCE_SYSTEM)
                    assertThat(r.getString(2)).isEqualTo("alphavantage-poller")
                }
            }
        }
    }

    @Test
    fun `a replayed pull appends a newer row and the asOfTime read still resolves`() {
        val dataset = dataset()
        val svc = service(dailyPayload("101.0"))
        svc.sync(target(dataset))
        val loaded =
            store
                .query(TimeSeriesQuery(dataset, LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-26")))
                .maxOf { it.recordedAt ?: Instant.MIN }

        service(dailyPayload("101.5")).sync(target(dataset))

        val range = TimeSeriesQuery(dataset, LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-26"), fields = setOf("close"))
        assertThat(store.query(range).single().value.toPlainString()).isEqualTo("101.5000000000")
        assertThat(store.query(range.copy(asOfTime = loaded)).single().value.toPlainString()).isEqualTo("101.0000000000")
    }

    @Test
    fun `a 200 error envelope fails the target and writes nothing`() {
        val dataset = dataset()
        val result = service("""{"Note":"Our standard API rate limit is 25 requests per day"}""").sync(target(dataset))

        assertThat(result.error).contains("rate limit")
        assertThat(result.pointsWritten).isZero()
        assertThat(store.query(TimeSeriesQuery(dataset, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))).isEmpty()
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")
    }
}
