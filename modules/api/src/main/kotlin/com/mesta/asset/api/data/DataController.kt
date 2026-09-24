package com.mesta.asset.api.data

import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.ingestion.persistence.TimeSeriesQuery
import com.mesta.asset.ingestion.persistence.TimeSeriesReader
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `GET /api/v1/data/{datasetId}?startDate&endDate&fields&asOfTime&since` (Marquee data service, #6 slice 6).
 * Tenant-scoped through [TenantDirectory]: any role in the dataset's tenant may read it; another tenant's
 * dataset and an unknown id are both 404 (default deny). `asOfTime` is the point-in-time view (§10.8).
 */
@RestController
class DataController(
    private val series: TimeSeriesReader,
    private val tenants: TenantDirectory,
) {
    @GetMapping("/api/v1/data/{datasetId}")
    fun data(
        @PathVariable datasetId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
        @RequestParam(required = false) fields: Set<String>?,
        @RequestParam(required = false) asOfTime: Instant?,
        @RequestParam(required = false) since: Instant?,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<DataResponse> {
        if (endDate.isBefore(startDate)) return ResponseEntity.badRequest().build()
        val tenantId = series.datasetTenant(datasetId) ?: return ResponseEntity.notFound().build()
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return ResponseEntity.notFound().build()
        if (tenants.tenantsOf(userId).none { it.tenantId == tenantId }) return ResponseEntity.notFound().build()
        val observations = series.query(TimeSeriesQuery(datasetId, startDate, endDate, fields?.takeIf { it.isNotEmpty() }, asOfTime, since))
        return ResponseEntity.ok(
            DataResponse(
                datasetId = datasetId,
                startDate = startDate,
                endDate = endDate,
                asOfTime = asOfTime,
                since = since,
                observations = observations.map { ObservationView(it.seriesKey, it.field, it.effectiveDate, it.value, it.recordedAt!!) },
            ),
        )
    }

    data class ObservationView(
        val seriesKey: String,
        val field: String,
        val effectiveDate: LocalDate,
        val value: BigDecimal,
        val recordedAt: Instant,
    )

    data class DataResponse(
        val datasetId: UUID,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val asOfTime: Instant?,
        val since: Instant?,
        val observations: List<ObservationView>,
    )
}
