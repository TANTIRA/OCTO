package com.mesta.asset.ingestion.persistence

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** JDBC access to `mesta.timeseries_observation` (V12). Insert and read only; the table is append-only. */
class JdbcTimeSeriesStore(
    private val dataSource: DataSource,
) : TimeSeriesReader {
    /** Writes [observations] in one transaction and returns them with the database's `recorded_at`, in input order. */
    fun write(
        observations: List<Observation>,
        provenance: ObservationProvenance,
    ): List<Observation> {
        val sql =
            """
            insert into mesta.timeseries_observation (dataset_id, series_key, field, effective_date, value, supersedes_id, rationale,
                                                      source_system, actor, ingestion_run_id, correlation_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning recorded_at
            """.trimIndent()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val written =
                    connection.prepareStatement(sql).use { statement ->
                        observations.map { observation ->
                            statement.setObject(1, observation.datasetId)
                            statement.setString(2, observation.seriesKey)
                            statement.setString(3, observation.field)
                            statement.setObject(4, observation.effectiveDate)
                            statement.setBigDecimal(5, observation.value)
                            statement.setObject(6, observation.supersedesId)
                            statement.setString(7, observation.rationale)
                            statement.setString(8, provenance.sourceSystem)
                            statement.setString(9, provenance.actor)
                            statement.setObject(10, provenance.ingestionRunId)
                            statement.setObject(11, provenance.correlationId)
                            statement.executeQuery().use { rows ->
                                rows.next()
                                observation.copy(recordedAt = rows.getObject("recorded_at", OffsetDateTime::class.java).toInstant())
                            }
                        }
                    }
                connection.commit()
                return written
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    override fun datasetTenant(datasetId: UUID): UUID? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select tenant_id from mesta.dataset where id = ?").use { statement ->
                statement.setObject(1, datasetId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("tenant_id", UUID::class.java) else null }
            }
        }

    /** `distinct on` keeps the latest recorded row per series, field and effective date among the rows the filters admit. */
    override fun query(query: TimeSeriesQuery): List<Observation> {
        val conditions = mutableListOf("dataset_id = ?", "effective_date between ? and ?")
        val parameters = mutableListOf<Any>(query.datasetId, query.startDate, query.endDate)
        query.asOfTime?.let {
            conditions += "recorded_at <= ?"
            parameters += it.atOffset(ZoneOffset.UTC)
        }
        query.since?.let {
            conditions += "recorded_at > ?"
            parameters += it.atOffset(ZoneOffset.UTC)
        }
        query.fields?.let {
            conditions += "field = any(?)"
            parameters.add(it.toTypedArray())
        }
        val sql =
            """
            select distinct on (series_key, field, effective_date)
                   series_key, field, effective_date, value, recorded_at, supersedes_id, rationale
            from mesta.timeseries_observation
            where ${conditions.joinToString(" and ")}
            order by series_key, field, effective_date, recorded_at desc
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { i, value ->
                    if (value is Array<*>) {
                        statement.setArray(
                            i + 1,
                            connection.createArrayOf("text", value),
                        )
                    } else {
                        statement.setObject(i + 1, value)
                    }
                }
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows else null }
                        .map {
                            Observation(
                                datasetId = query.datasetId,
                                seriesKey = it.getString("series_key"),
                                field = it.getString("field"),
                                effectiveDate = it.getObject("effective_date", LocalDate::class.java),
                                value = it.getBigDecimal("value"),
                                recordedAt = it.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                                supersedesId = it.getObject("supersedes_id", UUID::class.java),
                                rationale = it.getString("rationale"),
                            )
                        }.toList()
                }
            }
        }
    }
}
