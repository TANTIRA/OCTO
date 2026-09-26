package com.mesta.asset.ingestion.persistence

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One bi-temporal fact of `mesta.timeseries_observation` (V12): [effectiveDate] is when the value is true of
 * the world, [recordedAt] is when the platform learned it (assigned by the database, so null before a write).
 * A correction is a new observation that supersedes the old one with a [rationale].
 */
data class Observation(
    val datasetId: UUID,
    val seriesKey: String,
    val field: String,
    val effectiveDate: LocalDate,
    val value: BigDecimal,
    val recordedAt: Instant? = null,
    val supersedesId: UUID? = null,
    val rationale: String? = null,
)

/** Where a batch of observations came from, for the provenance columns V12 requires. */
data class ObservationProvenance(
    val sourceSystem: String,
    val actor: String,
    val ingestionRunId: UUID,
    val correlationId: UUID,
)

/**
 * The Marquee-shaped read (`GET /v1/data/{dataset}?startDate&endDate&fields&asOfTime&since`, #6 slice 6).
 * [asOfTime] gives the point-in-time view §10.8 needs: only rows recorded on or before it count, and the
 * latest of them per series, field and effective date is the value in force. [since] keeps only rows
 * recorded after it, i.e. what changed. [fields] null means every field.
 */
data class TimeSeriesQuery(
    val datasetId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val fields: Set<String>? = null,
    val asOfTime: Instant? = null,
    val since: Instant? = null,
) {
    init {
        require(!endDate.isBefore(startDate)) { "endDate must not be before startDate" }
        require(fields == null || fields.isNotEmpty()) { "fields must name at least one field, or be null for all" }
    }
}

/** What the data endpoint reads. Tenant scoping is the caller's job, from [datasetTenant]. */
interface TimeSeriesReader {
    /** The tenant a dataset belongs to, or null for an unknown dataset. */
    fun datasetTenant(datasetId: UUID): UUID?

    fun query(query: TimeSeriesQuery): List<Observation>
}

/** The write side ingestion adapters depend on; `JdbcTimeSeriesStore` implements it. */
interface TimeSeriesWriter {
    fun write(
        observations: List<Observation>,
        provenance: ObservationProvenance,
    ): List<Observation>
}
