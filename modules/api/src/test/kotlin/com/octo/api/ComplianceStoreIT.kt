package com.octo.api

import com.octo.analytics.CoverageReport
import com.octo.persistence.TenantScope
import com.octo.recon.compliance.ComplianceCheck
import com.octo.recon.compliance.ComplianceInputs
import com.octo.recon.compliance.ComplianceRule
import com.octo.recon.compliance.Result
import com.octo.recon.compliance.evaluate
import com.octo.recon.compliance.persistence.ComplianceProvenance
import com.octo.recon.compliance.persistence.JdbcComplianceStore
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** `JdbcComplianceStore` against the real V14 schema: rule definitions round-trip by version, evaluations record, breaches dedupe. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class ComplianceStoreIT {
    private val dataSource by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("octo")
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
                        "insert into octo.tenant (slug, display_name, source_system, correlation_id) " +
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
                        "insert into octo.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id) " +
                            "values ('review', 'compliance-breach', 'x', 'runner', 'test', gen_random_uuid()) returning id",
                    ).use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
            }
        }

    private fun define(
        tenantId: UUID,
        ruleId: String,
        name: String,
        check: ComplianceCheck,
        expectedVersion: Int? = null,
        scope: TenantScope = TenantScope.All,
    ) = store.defineRule(tenantId, ruleId, name, check, expectedVersion, provenance, scope)

    @Test
    fun `every check round-trips through its json definition and only the latest active version is returned`() {
        val tenantId = tenant()
        define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.25")))
        define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.30")))
        define(tenantId, "eur", "EUR cap", ComplianceCheck.CurrencyExposureLimit(Currency.getInstance("EUR"), BigDecimal("0.4")))
        define(tenantId, "cov", "Coverage", ComplianceCheck.CoverageFloor(BigDecimal("1.2")))

        val rules = store.activeRules(tenantId, TenantScope.All)
        assertThat(rules.map { it.id to it.version }).containsExactly("conc" to 2, "cov" to 1, "eur" to 1)
        assertThat((rules[0].check as ComplianceCheck.ConcentrationLimit).maxFraction).isEqualByComparingTo("0.30")
        assertThat((rules[2].check as ComplianceCheck.CurrencyExposureLimit).currency.currencyCode).isEqualTo("EUR")
        assertThat(store.activeRules(tenant(), TenantScope.All)).isEmpty()
    }

    @Test
    fun `the store allocates versions and a caller that predicts the wrong one is refused`() {
        val tenantId = tenant()
        assertThat(
            define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.25")), expectedVersion = 1).version,
        ).isEqualTo(1)

        // a second caller that read version 1 and proposes 2 wins; a stale guess of 1 is a conflict
        assertThatThrownBy {
            define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.30")), expectedVersion = 1)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(
            define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.30")), expectedVersion = 2).version,
        ).isEqualTo(2)
        assertThatThrownBy {
            define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.35")), expectedVersion = 5)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `concurrent definitions serialize on the rule lock and never share a version`() {
        val tenantId = tenant()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val versions =
                (1..2)
                    .map {
                        pool.submit<Int> {
                            define(
                                tenantId,
                                "conc",
                                "Concentration",
                                ComplianceCheck.ConcentrationLimit(BigDecimal("0.25")),
                            ).version
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(versions).containsExactlyInAnyOrder(1, 2)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a retired rule leaves the active set and a later define re-activates it`() {
        val tenantId = tenant()
        assertThat(store.retire(tenantId, "conc", provenance, TenantScope.All)).isNull()

        define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.25")))
        define(tenantId, "cov", "Coverage", ComplianceCheck.CoverageFloor(BigDecimal("1.2")))

        // the tombstone is a version like any other; the runner no longer sees the rule at all
        assertThat(store.retire(tenantId, "conc", provenance, TenantScope.All)!!.version).isEqualTo(2)
        assertThat(store.activeRules(tenantId, TenantScope.All).map { it.id }).containsExactly("cov")
        // retiring an already-retired rule is idempotent — no extra tombstone rows
        assertThat(store.retire(tenantId, "conc", provenance, TenantScope.All)!!.version).isEqualTo(2)
        // a later version re-activates
        assertThat(define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.30"))).version)
            .isEqualTo(3)
        assertThat(store.activeRules(tenantId, TenantScope.All).map { it.id }).containsExactlyInAnyOrder("conc", "cov")
    }

    @Test
    fun `evaluations record with their task, and a breach on the same key is found and refused twice`() {
        val tenantId = tenant()
        val floor = ComplianceRule("cov", 1, "Coverage", ComplianceCheck.CoverageFloor(BigDecimal("1.2")))
        val coverage = CoverageReport(asOf, asOf.plusYears(1), usd, "base", null, null, null, null, BigDecimal("1.1"))
        val breach = evaluate(listOf(floor), ComplianceInputs("fund-1", asOf, coverage = coverage)).single()
        assertThat(breach.result).isEqualTo(Result.BREACH)
        assertThat(store.breachTask(tenantId, breach, TenantScope.All)).isNull()

        val taskId = task()
        store.record(tenantId, breach, taskId, UUID.randomUUID(), TenantScope.All)
        assertThat(store.breachTask(tenantId, breach, TenantScope.All)).isEqualTo(taskId)
        assertThatThrownBy {
            store.record(
                tenantId,
                breach,
                task(),
                UUID.randomUUID(),
                TenantScope.All,
            )
        }.isInstanceOf(SQLException::class.java)

        val pass = breach.copy(result = Result.PASS, explanation = "restated")
        store.record(tenantId, pass, null, UUID.randomUUID(), TenantScope.All)
        // A task is allowed only on a breach.
        assertThatThrownBy {
            store.record(
                tenantId,
                pass,
                task(),
                UUID.randomUUID(),
                TenantScope.All,
            )
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `a scope that does not admit the tenant sees nothing and refuses writes`() {
        val tenantId = tenant()
        val elsewhere = TenantScope.Tenants(listOf(UUID.randomUUID()))
        define(tenantId, "conc", "Concentration", ComplianceCheck.ConcentrationLimit(BigDecimal("0.25")))
        val breach =
            evaluate(
                listOf(ComplianceRule("cov", 1, "Coverage", ComplianceCheck.CoverageFloor(BigDecimal("1.2")))),
                ComplianceInputs(
                    "fund-1",
                    asOf,
                    coverage = CoverageReport(asOf, asOf.plusYears(1), usd, "base", null, null, null, null, BigDecimal("1.1")),
                ),
            ).single()
        assertThat(store.activeRules(tenantId, elsewhere)).isEmpty()
        assertThat(store.breachTask(tenantId, breach, elsewhere)).isNull()
        assertThatThrownBy {
            store.defineRule(
                tenantId,
                "x",
                "X",
                ComplianceCheck.CoverageFloor(BigDecimal("1.0")),
                null,
                provenance,
                elsewhere,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.retire(tenantId, "conc", provenance, elsewhere) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.record(tenantId, breach, null, UUID.randomUUID(), elsewhere) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("octo")
                .withUsername("octo")
                .withPassword("octo")
    }
}
