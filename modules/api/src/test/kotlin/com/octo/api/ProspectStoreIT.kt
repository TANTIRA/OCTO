package com.octo.api

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.TenantScope
import com.octo.dealsourcing.persistence.JdbcProspectStore
import com.octo.dealsourcing.persistence.JdbcScreeningRuleStore
import com.octo.dealsourcing.persistence.ProspectProvenance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** `JdbcProspectStore` against the real V18 schema: stage replay, transition validation, and append-only enforcement. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class ProspectStoreIT {
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
    private val store by lazy { JdbcProspectStore(dataSource) }
    private val provenance = ProspectProvenance("integration-test", UUID.randomUUID())
    private val t0 = Instant.parse("2026-09-01T00:00:00Z")

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

    /** A workflow_task row the `invested` event can name — V19's FK needs the task to exist. */
    private fun task(): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "insert into mesta.workflow_task (kind, subject_type, subject_id, requested_by, source_system, correlation_id) " +
                            "values ('approval', 'prospect', 'x', 'test', 'test', gen_random_uuid()) returning id",
                    ).use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java)
                    }
            }
        }

    private fun prospect(tenantId: UUID) =
        Prospect(
            UUID.randomUUID(),
            tenantId,
            "acme-logistics",
            ProspectSource.CRM,
            sector = "logistics",
            region = "SEA",
            description = null,
            registeredAt = t0,
        )

    @Test
    fun `a prospect registers in sourced, moves one stage at a time, and invests from ic-review`() {
        val tenantId = tenant()
        val p = prospect(tenantId)
        store.create(p, "analyst-1", provenance, TenantScope.All)

        assertThat(store.load(p.id, TenantScope.All)!!.stage).isEqualTo(ProspectStage.SOURCED)
        assertThat(store.listAtStage(tenantId, ProspectStage.SOURCED, TenantScope.All).map { it.prospect.id }).containsExactly(p.id)

        store.append(
            p.id,
            ProspectEvent.Advanced("analyst-1", t0.plusSeconds(1), ProspectStage.SOURCED, ProspectStage.SCREENING),
            provenance,
            TenantScope.All,
        )
        store.append(
            p.id,
            ProspectEvent.Advanced("analyst-1", t0.plusSeconds(2), ProspectStage.SCREENING, ProspectStage.DUE_DILIGENCE),
            provenance,
            TenantScope.All,
        )
        store.append(
            p.id,
            ProspectEvent.Advanced("approver-1", t0.plusSeconds(3), ProspectStage.DUE_DILIGENCE, ProspectStage.IC_REVIEW),
            provenance,
            TenantScope.All,
        )
        val invested =
            store.append(
                p.id,
                ProspectEvent.Invested("ic-chair", t0.plusSeconds(4), "conviction in the corridor thesis", task()),
                provenance,
                TenantScope.All,
            )

        assertThat(invested.stage).isEqualTo(ProspectStage.INVESTED)
        assertThat(invested.decidedBy).isEqualTo("ic-chair")
        assertThat(store.listAtStage(tenantId, ProspectStage.SOURCED, TenantScope.All)).isEmpty()
        assertThat(store.listAtStage(tenantId, ProspectStage.INVESTED, TenantScope.All).map { it.prospect.id }).containsExactly(p.id)
        assertThatThrownBy {
            store.append(
                p.id,
                ProspectEvent.Passed("analyst-1", t0.plusSeconds(5), ProspectStage.INVESTED, "late pass"),
                provenance,
                TenantScope.All,
            )
        }.isInstanceOf(IllegalArgumentException::class.java) // terminal stage accepts nothing
    }

    @Test
    fun `skipping a stage, lying about the current stage, and a rationale-free decision are rejected`() {
        val tenantId = tenant()
        val p = prospect(tenantId)
        store.create(p, "analyst-1", provenance, TenantScope.All)

        assertThatThrownBy {
            store.append(
                p.id,
                ProspectEvent.Advanced("analyst-1", t0.plusSeconds(1), ProspectStage.SOURCED, ProspectStage.DUE_DILIGENCE),
                provenance,
                TenantScope.All,
            )
        }.isInstanceOf(IllegalArgumentException::class.java) // one stage at a time
        assertThatThrownBy {
            store.append(
                p.id,
                ProspectEvent.Advanced("analyst-1", t0.plusSeconds(1), ProspectStage.DUE_DILIGENCE, ProspectStage.IC_REVIEW),
                provenance,
                TenantScope.All,
            )
        }.isInstanceOf(IllegalArgumentException::class.java) // from must equal the replayed stage
        assertThatThrownBy {
            store.append(
                p.id,
                ProspectEvent.Invested("ic-chair", t0.plusSeconds(1), "early conviction", task()),
                provenance,
                TenantScope.All,
            )
        }.isInstanceOf(IllegalArgumentException::class.java) // invested only from ic-review
        assertThat(store.load(p.id, TenantScope.All)!!.stage).isEqualTo(ProspectStage.SOURCED) // nothing written on rejection
    }

    @Test
    fun `any open stage can pass with a rationale and rows are append-only`() {
        val tenantId = tenant()
        val p = prospect(tenantId)
        store.create(p, "analyst-1", provenance, TenantScope.All)
        store.append(
            p.id,
            ProspectEvent.Advanced("analyst-1", t0.plusSeconds(1), ProspectStage.SOURCED, ProspectStage.SCREENING),
            provenance,
            TenantScope.All,
        )
        val passed =
            store.append(
                p.id,
                ProspectEvent.Passed("analyst-1", t0.plusSeconds(2), ProspectStage.SCREENING, "thesis drift"),
                provenance,
                TenantScope.All,
            )

        assertThat(passed.stage).isEqualTo(ProspectStage.PASSED)
        assertThat(passed.decidedBy).isEqualTo("analyst-1")
        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection
                    .createStatement()
                    .execute("update mesta.prospect set name = 'x' where id = '${p.id}'")
            }
        }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy {
            dataSource.connection.use { connection ->
                connection
                    .createStatement()
                    .execute("delete from mesta.prospect_event where prospect_id = '${p.id}'")
            }
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `an unknown prospect is a clean miss`() {
        assertThat(store.load(UUID.randomUUID(), TenantScope.All)).isNull()
        assertThatThrownBy {
            store.append(
                UUID.randomUUID(),
                ProspectEvent.Passed("a", t0, ProspectStage.SOURCED, "nope"),
                provenance,
                TenantScope.All,
            )
        }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `screening rules version per rule_id and activeRules returns the newest of each`() {
        val rules = JdbcScreeningRuleStore(dataSource)
        val tenantId = tenant()
        assertThat(rules.define(tenantId, "mandate", "Mandate", """{"sectors":["saas"]}""", "admin", provenance, TenantScope.All))
            .isEqualTo(1)
        assertThat(
            rules.define(tenantId, "mandate", "Mandate v2", """{"sectors":["saas","logistics"]}""", "admin", provenance, TenantScope.All),
        ).isEqualTo(2)
        rules.define(tenantId, "esg", "ESG exclusions", "{}", "admin", provenance, TenantScope.All)

        val active = rules.activeRules(tenantId, TenantScope.All)
        assertThat(active.map { it.ruleId }.toSet()).isEqualTo(setOf("mandate", "esg"))
        assertThat(active.single { it.ruleId == "mandate" }.version).isEqualTo(2)
        assertThat(active.single { it.ruleId == "mandate" }.name).isEqualTo("Mandate v2")
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("octo_test")
                .withUsername("test")
                .withPassword("test")
    }
}
