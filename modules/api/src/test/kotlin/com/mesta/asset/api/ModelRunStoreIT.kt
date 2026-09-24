package com.mesta.asset.api

import com.mesta.asset.analytics.persistence.JdbcModelRunStore
import com.mesta.asset.analytics.persistence.ModelRun
import com.mesta.asset.analytics.persistence.ModelRunOutput
import com.mesta.asset.analytics.persistence.ModelStatus
import com.mesta.asset.analytics.persistence.OutputKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** V9's model-run store end to end as the runtime role (#102, methodology §9.6). Skipped when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
class ModelRunStoreIT {
    @Test
    fun `a run round-trips with its windows, parameters and labelled outputs`() {
        val run = run("hmm-2026q3")
        val outputs =
            listOf(
                ModelRunOutput(LocalDate.parse("2026-06-30"), OutputKind.FILTERED, """{"bull": 0.7, "bear": 0.3}"""),
                ModelRunOutput(LocalDate.parse("2026-03-31"), OutputKind.FILTERED, """{"bull": 0.6, "bear": 0.4}"""),
                ModelRunOutput(LocalDate.parse("2026-03-31"), OutputKind.SMOOTHED, """{"bull": 0.9, "bear": 0.1}"""),
            )

        val recorded = store.record(run, outputs)
        val loaded = store.load(run.id)

        assertThat(recorded.recordedAt).isNotNull()
        // jsonb normalizes key order and spacing, so the json fields are compared by content, the rest exactly.
        assertThat(loaded!!.copy(parameters = "", stateDefinitions = "", diagnostics = ""))
            .isEqualTo(recorded.copy(parameters = "", stateDefinitions = "", diagnostics = ""))
        assertThat(loaded.parameters).contains(""""durations": [10, 5]""")
        assertThat(loaded.stateDefinitions).contains(""""bull": "expansion"""")
        assertThat(loaded.trainingWindow).isEqualTo(LocalDate.parse("2020-01-01")..LocalDate.parse("2025-12-31"))
        assertThat(loaded.validationWindow).isNull()
        assertThat(loaded.status).isEqualTo(ModelStatus.CHALLENGER)
        assertThat(
            store.outputs(run.id, OutputKind.FILTERED).map {
                it.asOfDate
            },
        ).containsExactly(LocalDate.parse("2026-03-31"), LocalDate.parse("2026-06-30"))
        assertThat(store.outputs(run.id, OutputKind.SMOOTHED)).hasSize(1)
    }

    @Test
    fun `a version is immutable and a bad output rolls the whole run back`() {
        val first = run("factor-v1")
        store.record(first)
        assertSqlState(UNIQUE_VIOLATION) { store.record(first.copy(id = UUID.randomUUID(), parameters = """{"beta": 1.1}""")) }

        val broken = run("factor-v2")
        assertSqlState(CHECK_VIOLATION) {
            store.record(broken, listOf(ModelRunOutput(LocalDate.parse("2026-06-30"), OutputKind.FILTERED, "[1, 2]")))
        }
        assertThat(store.load(broken.id)).isNull()
    }

    @Test
    fun `runs are append-only and their json must be objects`() {
        val run = run("kalman-v1")
        store.record(run, listOf(ModelRunOutput(LocalDate.parse("2026-06-30"), OutputKind.FILTERED, """{"level": 1.5}""")))
        assertSqlState(RESTRICT_VIOLATION) { execute(owner, "update mesta.model_run set status = 'retired' where id = '${run.id}'") }
        assertSqlState(RESTRICT_VIOLATION) { execute(owner, "delete from mesta.model_run_output where run_id = '${run.id}'") }
        assertSqlState(CHECK_VIOLATION) { store.record(run("kalman-v2").copy(parameters = "42")) }
        assertSqlState(CHECK_VIOLATION) { store.record(run("kalman-v3").copy(supersedesId = run.id, rationale = " ")) }
    }

    private fun run(version: String) =
        ModelRun(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            family = "hmm",
            version = version,
            methodology = "quantitative-methodology §9.1–9.2 v1",
            frequency = "quarterly",
            dataVintage = LocalDate.parse("2026-06-30"),
            parameters = """{"transition": [[0.9, 0.1], [0.2, 0.8]], "durations": [10, 5]}""",
            status = ModelStatus.CHALLENGER,
            actor = "quant-1",
            correlationId = UUID.randomUUID(),
            stateDefinitions = """{"bull": "expansion", "bear": "contraction"}""",
            diagnostics = """{"logLikelihood": -12.5}""",
            trainingWindow = LocalDate.parse("2020-01-01")..LocalDate.parse("2025-12-31"),
        )

    private fun assertSqlState(
        state: String,
        action: () -> Unit,
    ) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo(state) }
    }

    private companion object {
        const val MIGRATOR = "mesta_migrator"
        const val RUNTIME = "mesta_app"
        const val UNIQUE_VIOLATION = "23505"
        const val CHECK_VIOLATION = "23514"
        const val RESTRICT_VIOLATION = "23001"

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("admin")
                .withPassword("admin")

        val superuser: DataSource by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }

        private val migrated by lazy<Unit> {
            execute(superuser, "create role $MIGRATOR login password '$MIGRATOR'")
            execute(superuser, "grant create on database ${postgres.databaseName} to $MIGRATOR")
            execute(superuser, "create role $RUNTIME login password '$RUNTIME'")
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, MIGRATOR, MIGRATOR)
                .locations("classpath:db/migration")
                .schemas("mesta")
                .placeholders(mapOf("runtime_role" to RUNTIME))
                .load()
                .migrate()
        }

        val owner: DataSource by lazy { migrated.let { DriverManagerDataSource(postgres.jdbcUrl, MIGRATOR, MIGRATOR) } }
        val runtime: DataSource by lazy { migrated.let { DriverManagerDataSource(postgres.jdbcUrl, RUNTIME, RUNTIME) } }
        val store: JdbcModelRunStore by lazy { JdbcModelRunStore(runtime) }

        val tenantId: UUID by lazy {
            owner.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "insert into mesta.tenant (slug, display_name, source_system, correlation_id) values ('acme', 'Acme', 'test', gen_random_uuid()) returning id",
                        ).use { rows ->
                            rows.next()
                            rows.getObject("id", UUID::class.java)
                        }
                }
            }
        }

        fun execute(
            dataSource: DataSource,
            sql: String,
        ) {
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
        }
    }
}
