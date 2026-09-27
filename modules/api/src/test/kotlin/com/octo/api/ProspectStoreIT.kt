package com.octo.api

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.persistence.JdbcProspectStore
import com.octo.dealsourcing.persistence.JdbcScreeningRuleStore
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.persistence.TenantScope
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
        assertThat(
            store.listAtStage(tenantId, ProspectStage.SOURCED, limit = 500, offset = 0, TenantScope.All).map {
                it.prospect.id
            },
        ).containsExactly(p.id)

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
        assertThat(store.listAtStage(tenantId, ProspectStage.SOURCED, limit = 500, offset = 0, TenantScope.All)).isEmpty()
        assertThat(
            store.listAtStage(tenantId, ProspectStage.INVESTED, limit = 500, offset = 0, TenantScope.All).map {
                it.prospect.id
            },
        ).containsExactly(p.id)
        assertThatThrownBy {
            store.append(
                p.id,
                ProspectEvent.Passed("analyst-1", t0.plusSeconds(5), ProspectStage.INVESTED, "late pass"),
                provenance,
                TenantScope.All,
            )
        }.isInstanceOf(IllegalArgumentException::class.java) // terminal stage accepts nothing

        val rows = store.history(p.id, TenantScope.All)!!
        assertThat(rows.map { it.eventType }).containsExactly("advanced", "advanced", "advanced", "invested")
        assertThat(rows.map { it.seq }).isSorted() // identity is table-global; append order is what matters
        assertThat(rows.last().rationale).isEqualTo("conviction in the corridor thesis")
        assertThat(rows.last().taskId).isNotNull()
        assertThat(rows.last().correlationId).isEqualTo(provenance.correlationId)
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
    fun `a tenants scope cannot read or write a prospect outside its list`() {
        val tenantId = tenant()
        val other = tenant()
        val p = prospect(tenantId)
        store.create(p, "analyst-1", provenance, TenantScope.All)

        val outside = TenantScope.Tenants(listOf(other))
        assertThat(store.load(p.id, outside)).isNull()
        assertThat(store.history(p.id, outside)).isNull()
        assertThat(store.listAtStage(tenantId, ProspectStage.SOURCED, limit = 500, offset = 0, outside)).isEmpty()
        assertThatThrownBy {
            store.append(
                p.id,
                ProspectEvent.Passed("analyst-1", t0.plusSeconds(1), ProspectStage.SOURCED, "out of scope"),
                provenance,
                outside,
            )
        }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy {
            store.create(prospect(other), "analyst-1", provenance, TenantScope.Tenants(listOf(tenantId)))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            store.importBatch(listOf(prospect(other)), "analyst-1", provenance, TenantScope.Tenants(listOf(tenantId)))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a tenants scope cannot write or read rules outside its list`() {
        val rules = JdbcScreeningRuleStore(dataSource)
        val tenantId = tenant()
        val other = tenant()
        assertThatThrownBy {
            rules.define(other, "mandate", "Mandate", "{}", "admin", provenance, TenantScope.Tenants(listOf(tenantId)))
        }.isInstanceOf(IllegalArgumentException::class.java)
        rules.define(tenantId, "mandate", "Mandate", "{}", "admin", provenance, TenantScope.All)
        assertThat(rules.activeRules(tenantId, TenantScope.Tenants(listOf(other)))).isEmpty()
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
    fun `bulk import dedupes on the external ref per tenant and source`() {
        val tenantId = tenant()
        val otherTenant = tenant()
        val a = prospect(tenantId).copy(name = "a", sourceRef = "crm-1")
        val dupe = prospect(tenantId).copy(name = "a-again", sourceRef = "crm-1") // same ref, same source
        val noRef = prospect(tenantId).copy(name = "manual")

        // first sync: the in-batch duplicate is skipped, the ref-less row registers
        assertThat(store.importBatch(listOf(a, dupe, noRef), "crm-sync", provenance, TenantScope.All))
            .containsExactlyInAnyOrder(a.id, noRef.id)
        // a re-sync is a no-op: V21's partial unique index is the arbiter
        assertThat(
            store.importBatch(listOf(prospect(tenantId).copy(sourceRef = "crm-1")), "crm-sync", provenance, TenantScope.All),
        ).isEmpty()
        // the same ref under another source, or another tenant, is a different record
        val otherSource = prospect(tenantId).copy(source = ProspectSource.REFERRAL, sourceRef = "crm-1")
        val otherTenantRow = prospect(otherTenant).copy(sourceRef = "crm-1")
        assertThat(store.importBatch(listOf(otherSource, otherTenantRow), "crm-sync", provenance, TenantScope.All))
            .containsExactlyInAnyOrder(otherSource.id, otherTenantRow.id)
        // a null ref never dedupes — a second ref-less import inserts again
        val noRef2 = prospect(tenantId).copy(name = "manual-2")
        assertThat(store.importBatch(listOf(noRef2), "crm-sync", provenance, TenantScope.All)).containsExactly(noRef2.id)
        assertThat(store.load(a.id, TenantScope.All)!!.prospect.sourceRef).isEqualTo("crm-1")
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

    @Test
    fun `a hand-written event row without its stages is refused by the database`() {
        val tenantId = tenant()
        val p = prospect(tenantId)
        store.create(p, "analyst-1", provenance, TenantScope.All)

        // V22: the columns are NOT NULL — a CHECK that only lists allowed values would still pass NULL.
        for (stages in listOf(null to "screening", "sourced" to null, null to null)) {
            assertThatThrownBy {
                insertEvent(p.id, stages.first, stages.second)
            }.isInstanceOf(SQLException::class.java)
        }
        // and a stage_from naming a terminal stage is refused too — nothing can leave one
        assertThatThrownBy {
            insertEvent(p.id, "passed", "screening")
        }.isInstanceOf(SQLException::class.java)
    }

    /** A raw event insert that bypasses the Kotlin machine — what the V22 constraints exist to refuse. */
    private fun insertEvent(
        prospectId: UUID,
        stageFrom: String?,
        stageTo: String?,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    insert into mesta.prospect_event (prospect_id, event_type, stage_from, stage_to, actor, occurred_at, correlation_id)
                    values (?, 'advanced', ?, ?, 'someone', now(), gen_random_uuid())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, prospectId)
                    statement.setString(2, stageFrom)
                    statement.setString(3, stageTo)
                    statement.executeUpdate()
                }
        }
    }

    @Test
    fun `the store itself bounds an import batch`() {
        val tenantId = tenant()
        val batch = (1..501).map { prospect(tenantId).copy(name = "p$it") }
        assertThatThrownBy {
            store.importBatch(batch, "crm-sync", provenance, TenantScope.All)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            store.importBatch(emptyList(), "crm-sync", provenance, TenantScope.All)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the store itself bounds a pipeline page`() {
        val tenantId = tenant()
        assertThatThrownBy {
            store.listAtStage(tenantId, ProspectStage.SOURCED, limit = 501, offset = 0, TenantScope.All)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            store.listAtStage(tenantId, ProspectStage.SOURCED, limit = 0, offset = 0, TenantScope.All)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            store.listAtStage(tenantId, ProspectStage.SOURCED, limit = 200, offset = -1, TenantScope.All)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a retired rule leaves the active set and a later define re-activates it`() {
        val rules = JdbcScreeningRuleStore(dataSource)
        val tenantId = tenant()
        assertThat(rules.retire(tenantId, "mandate", "admin", provenance, TenantScope.All)).isNull()

        rules.define(tenantId, "mandate", "Mandate", """{"sectors":["saas"]}""", "admin", provenance, TenantScope.All)
        rules.define(tenantId, "esg", "ESG", "{}", "admin", provenance, TenantScope.All)

        // the tombstone is a version like any other; the screen no longer sees the rule at all
        assertThat(rules.retire(tenantId, "mandate", "admin", provenance, TenantScope.All)).isEqualTo(2)
        assertThat(rules.activeRules(tenantId, TenantScope.All).map { it.ruleId }).containsExactly("esg")
        // retiring an already-retired rule is idempotent — no extra tombstone rows
        assertThat(rules.retire(tenantId, "mandate", "admin", provenance, TenantScope.All)).isEqualTo(2)
        // a later version re-activates
        assertThat(
            rules.define(tenantId, "mandate", "Mandate v3", """{"sectors":["saas","logistics"]}""", "admin", provenance, TenantScope.All),
        ).isEqualTo(3)
        assertThat(rules.activeRules(tenantId, TenantScope.All).map { it.ruleId }).containsExactlyInAnyOrder("mandate", "esg")
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
