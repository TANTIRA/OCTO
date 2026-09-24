package com.mesta.asset.api

import com.mesta.asset.iborcore.commitmentPosition
import com.mesta.asset.iborcore.latestValuation
import com.mesta.asset.iborcore.persistence.JdbcIborReader
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The IBOR read path end to end: real Flyway migrations, rows inserted by SQL, read back through
 * `JdbcIborReader`, then derived by `ibor-core`. Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class IborReaderIT {
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
    private val reader by lazy { JdbcIborReader(dataSource) }
    private val later = Instant.now().plusSeconds(60)

    @Test
    fun `the reader returns the supersession component and derivation resolves it`() {
        val a = ledger("contribution", "-100", "2022-01-10T00:00:00Z")
        val b = ledger("contribution", "-50", "2023-01-10T00:00:00Z")
        val c = ledger("contribution", "-120", "2022-01-10T00:00:00Z", supersedes = a)
        val d = ledger("contribution", "-110", "2022-01-10T00:00:00Z", supersedes = c)
        ledger("contribution", "-999", "2022-01-10T00:00:00Z") // unrelated row, must not load

        val rows = reader.ledgerEvents(setOf(a, b))
        assertThat(rows.map { it.id }).containsExactlyInAnyOrder(a, b, c, d)

        val position = commitmentPosition(rows, setOf(a, b, c, d), later, ZoneOffset.UTC)
        assertThat(position.called).isEqualByComparingTo("160")
        assertThat(position.cashFlows.map { it.first })
            .containsExactly(LocalDate.parse("2022-01-10"), LocalDate.parse("2023-01-10"))
    }

    @Test
    fun `latest valuation follows the correction read from the database`() {
        val original = valuation("100", "2024-12-31")
        val fix = valuation("105", "2024-12-31", supersedes = original)

        val rows = reader.valuationEvents(setOf(original))
        assertThat(rows.map { it.id }).containsExactlyInAnyOrder(original, fix)

        val nav = latestValuation(rows, setOf(original, fix), LocalDate.parse("2024-12-31"), later)
        assertThat(nav?.amount).isEqualByComparingTo(BigDecimal("105"))
        assertThat(reader.valuationEvents(emptySet())).isEmpty()
    }

    private fun ledger(
        flowType: String,
        amount: String,
        occurredAt: String,
        supersedes: UUID? = null,
    ): UUID =
        insert(
            """
            insert into mesta.ledger_event
                (flow_type, monetary_amount, currency_code, occurred_at, supersedes_id, rationale,
                 source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, 'USD', ?::timestamptz, ?, ?, 'test', 'integration-test', gen_random_uuid(), gen_random_uuid())
            returning id
            """.trimIndent(),
            flowType,
            BigDecimal(amount),
            occurredAt,
            supersedes,
            supersedes?.let { "Corrected allocation." },
        )

    private fun valuation(
        amount: String,
        asOf: String,
        supersedes: UUID? = null,
    ): UUID =
        insert(
            """
            insert into mesta.valuation_event
                (monetary_amount, currency_code, as_of_date, valuation_method, supersedes_id,
                 rationale, source_system, actor, ingestion_run_id, correlation_id)
            values (?, 'USD', ?::date, 'mark-to-model', ?, ?, 'test', 'integration-test', gen_random_uuid(), gen_random_uuid())
            returning id
            """.trimIndent(),
            BigDecimal(amount),
            asOf,
            supersedes,
            supersedes?.let { "Restated after audit." },
        )

    private fun insert(
        sql: String,
        vararg params: Any?,
    ): UUID =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getObject(1, UUID::class.java)
                }
            }
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
