package com.mesta.asset.api

import com.mesta.asset.analytics.CoverageReport
import com.mesta.asset.recon.compliance.ComplianceCheck
import com.mesta.asset.recon.compliance.ComplianceInputs
import com.mesta.asset.recon.compliance.ComplianceRule
import com.mesta.asset.recon.compliance.Result
import com.mesta.asset.recon.compliance.evaluate
import com.mesta.asset.recon.compliance.persistence.ComplianceProvenance
import com.mesta.asset.recon.compliance.persistence.JdbcComplianceStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.SQLException
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/** `JdbcComplianceStore` against the real V14 schema: rule definitions round-trip by version, evaluations record, breaches dedupe. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class ComplianceStoreIT {
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
    private val store by lazy { JdbcComplianceStore(dataSource) }
    private val provenance = ComplianceProvenance("compliance-officer", UUID.randomUUID())
    private val asOf = LocalDate.parse("2026-06-30")
    private val usd = Currency.getInstance("USD")

    private fun tenant(): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "insert into mesta.tenant (slug, display_name, source_system, correlation_id) " +
                            "values ('t-${UUID.randomUUID().toString().take(8)}', 'T', 'test', gen_random_uuid()) returning id",
                    ).use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
            }
        }

    private fun task(): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "insert into mesta.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id) " +
                            "values ('review', 'compliance-breach', 'x', 'runner', 'test', gen_random_uuid()) returning id",
                    ).use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
            }
        }

    @Test
    fun `every check round-trips through its json definition and only the latest active version is returned`() {
        val tenantId = tenant()
        store.defineRule(
            tenantId,
            ComplianceRule("conc", 1, "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.25"))),
            provenance,
        )
        store.defineRule(
            tenantId,
            ComplianceRule("conc", 2, "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.30"))),
            provenance,
        )
        store.defineRule(
            tenantId,
            ComplianceRule("eur", 1, "EUR cap", ComplianceCheck.CurrencyExposureLimit(Currency.getInstance("EUR"), BigDecimal("0.4"))),
            provenance,
        )
        store.defineRule(tenantId, ComplianceRule("cov", 1, "Coverage", ComplianceCheck.CoverageFloor(BigDecimal("1.2"))), provenance)

        val rules = store.activeRules(tenantId)
        assertThat(rules.map { it.id to it.version }).containsExactly("conc" to 2, "cov" to 1, "eur" to 1)
        assertThat((rules[0].check as ComplianceCheck.ConcentrationLimit).maxFraction).isEqualByComparingTo("0.30")
        assertThat((rules[2].check as ComplianceCheck.CurrencyExposureLimit).currency.currencyCode).isEqualTo("EUR")
        assertThat(store.activeRules(tenant())).isEmpty()
        assertThatThrownBy { store.defineRule(tenantId, rules[0], provenance) }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `evaluations record with their task, and a breach on the same key is found and refused twice`() {
        val tenantId = tenant()
        val floor = ComplianceRule("cov", 1, "Coverage", ComplianceCheck.CoverageFloor(BigDecimal("1.2")))
        val coverage = CoverageReport(asOf, asOf.plusYears(1), usd, "base", null, null, null, null, BigDecimal("1.1"))
        val breach = evaluate(listOf(floor), ComplianceInputs("fund-1", asOf, coverage = coverage)).single()
        assertThat(breach.result).isEqualTo(Result.BREACH)
        assertThat(store.breachTask(tenantId, breach)).isNull()

        val taskId = task()
        store.record(tenantId, breach, taskId, UUID.randomUUID())
        assertThat(store.breachTask(tenantId, breach)).isEqualTo(taskId)
        assertThatThrownBy { store.record(tenantId, breach, task(), UUID.randomUUID()) }.isInstanceOf(SQLException::class.java)

        val pass = breach.copy(result = Result.PASS, explanation = "restated")
        store.record(tenantId, pass, null, UUID.randomUUID())
        // A task is allowed only on a breach.
        assertThatThrownBy { store.record(tenantId, pass, task(), UUID.randomUUID()) }.isInstanceOf(SQLException::class.java)
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
