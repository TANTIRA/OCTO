package com.mesta.asset.analytics.persistence

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** §9.6 status of a run against its peers. */
enum class ModelStatus(
    val wireValue: String,
) {
    BENCHMARK("benchmark"),
    CHALLENGER("challenger"),
    RETIRED("retired"),
    ;

    companion object {
        fun fromWireValue(value: String) = entries.first { it.wireValue == value }
    }
}

/** Filtered output is valid at decision time; smoothed output used future observations and is historical-only. */
enum class OutputKind(
    val wireValue: String,
) {
    FILTERED("filtered"),
    SMOOTHED("smoothed"),
    ;

    companion object {
        fun fromWireValue(value: String) = entries.first { it.wireValue == value }
    }
}

/**
 * One row of `mesta.model_run` (V9). The json fields are jsonb object text: the caller serializes its
 * `EstimatedModel`, `KalmanFiltered` or `FactorModel` however it likes, and the database refuses anything
 * that is not an object. [version] is immutable per (tenant, family): a re-run with other parameters is a
 * new version, and a correction supersedes with a [rationale] (§10.5).
 */
data class ModelRun(
    val id: UUID,
    val tenantId: UUID,
    val family: String,
    val version: String,
    val methodology: String,
    val frequency: String,
    val dataVintage: LocalDate,
    val parameters: String,
    val status: ModelStatus,
    val actor: String,
    val correlationId: UUID,
    val stateDefinitions: String = "{}",
    val featureSet: String = "{}",
    val diagnostics: String = "{}",
    val trainingWindow: ClosedRange<LocalDate>? = null,
    val validationWindow: ClosedRange<LocalDate>? = null,
    val supersedesId: UUID? = null,
    val rationale: String? = null,
    val recordedAt: Instant? = null,
)

data class ModelRunOutput(
    val asOfDate: LocalDate,
    val kind: OutputKind,
    val values: String,
)

/** JDBC access to `mesta.model_run` and `mesta.model_run_output` (V9). Insert and read only; the tables are append-only. */
class JdbcModelRunStore(
    private val dataSource: DataSource,
) {
    /** Stores [run] and its [outputs] in one transaction. Returns the run as recorded, with the database's `recorded_at`. */
    fun record(
        run: ModelRun,
        outputs: List<ModelRunOutput> = emptyList(),
    ): ModelRun {
        val runSql =
            """
            insert into mesta.model_run (id, tenant_id, model_family, model_version, methodology, state_definitions, feature_set,
                                         frequency, data_vintage, training_start, training_end, validation_start, validation_end,
                                         parameters, diagnostics, status, supersedes_id, rationale, actor, correlation_id)
            values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
            returning recorded_at
            """.trimIndent()
        val outputSql = "insert into mesta.model_run_output (run_id, as_of_date, kind, values) values (?, ?, ?, ?::jsonb)"
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val recordedAt =
                    connection.prepareStatement(runSql).use { statement ->
                        statement.setObject(1, run.id)
                        statement.setObject(2, run.tenantId)
                        statement.setString(3, run.family)
                        statement.setString(4, run.version)
                        statement.setString(5, run.methodology)
                        statement.setString(6, run.stateDefinitions)
                        statement.setString(7, run.featureSet)
                        statement.setString(8, run.frequency)
                        statement.setObject(9, run.dataVintage)
                        statement.setObject(10, run.trainingWindow?.start)
                        statement.setObject(11, run.trainingWindow?.endInclusive)
                        statement.setObject(12, run.validationWindow?.start)
                        statement.setObject(13, run.validationWindow?.endInclusive)
                        statement.setString(14, run.parameters)
                        statement.setString(15, run.diagnostics)
                        statement.setString(16, run.status.wireValue)
                        statement.setObject(17, run.supersedesId)
                        statement.setString(18, run.rationale)
                        statement.setString(19, run.actor)
                        statement.setObject(20, run.correlationId)
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getObject("recorded_at", OffsetDateTime::class.java).toInstant()
                        }
                    }
                connection.prepareStatement(outputSql).use { statement ->
                    for (output in outputs) {
                        statement.setObject(1, run.id)
                        statement.setObject(2, output.asOfDate)
                        statement.setString(3, output.kind.wireValue)
                        statement.setString(4, output.values)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
                return run.copy(recordedAt = recordedAt)
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    fun load(id: UUID): ModelRun? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select * from mesta.model_run where id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toRun() else null }
            }
        }

    /** A run's output of one [kind], in date order. Callers asking for [OutputKind.SMOOTHED] are asking for history, not a signal. */
    fun outputs(
        runId: UUID,
        kind: OutputKind,
    ): List<ModelRunOutput> =
        dataSource.connection.use { connection ->
            val sql = "select as_of_date, kind, values::text from mesta.model_run_output where run_id = ? and kind = ? order by as_of_date"
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, runId)
                statement.setString(2, kind.wireValue)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows else null }
                        .map {
                            ModelRunOutput(
                                it.getObject("as_of_date", LocalDate::class.java),
                                OutputKind.fromWireValue(it.getString("kind")),
                                it.getString("values"),
                            )
                        }.toList()
                }
            }
        }

    private fun ResultSet.toRun() =
        ModelRun(
            id = getObject("id", UUID::class.java),
            tenantId = getObject("tenant_id", UUID::class.java),
            family = getString("model_family"),
            version = getString("model_version"),
            methodology = getString("methodology"),
            frequency = getString("frequency"),
            dataVintage = getObject("data_vintage", LocalDate::class.java),
            parameters = getString("parameters"),
            status = ModelStatus.fromWireValue(getString("status")),
            actor = getString("actor"),
            correlationId = getObject("correlation_id", UUID::class.java),
            stateDefinitions = getString("state_definitions"),
            featureSet = getString("feature_set"),
            diagnostics = getString("diagnostics"),
            trainingWindow = window("training_start", "training_end"),
            validationWindow = window("validation_start", "validation_end"),
            supersedesId = getObject("supersedes_id", UUID::class.java),
            rationale = getString("rationale"),
            recordedAt = getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun ResultSet.window(
        start: String,
        end: String,
    ): ClosedRange<LocalDate>? {
        val from = getObject(start, LocalDate::class.java) ?: return null
        return from..getObject(end, LocalDate::class.java)
    }
}
