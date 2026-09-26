package com.mesta.asset.api.reconciliation

import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import com.mesta.asset.recon.matching.SourceRecord
import com.mesta.asset.recon.matching.Tolerance
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.UUID

/**
 * `POST /api/v1/reconciliations` (#6 slice 9): the caller submits a source system's records; the run compares them
 * with the current ledger events of that system and returns the matches count and every break with the task
 * reviewing it. Working roles in the tenant only; outside it 404. Duplicate keys in the batch are 400; duplicate
 * current ledger events for a key are 409, because the ledger itself needs a supersession first.
 */
@RestController
class ReconciliationController(
    private val runner: ReconciliationRunner,
    private val tenants: TenantDirectory,
) {
    @PostMapping("/api/v1/reconciliations")
    fun run(
        @Valid @RequestBody body: RunBody,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RunView> {
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return ResponseEntity.notFound().build()
        val role = tenants.tenantsOf(userId).firstOrNull { it.tenantId == body.tenantId }?.role ?: return ResponseEntity.notFound().build()
        if (role == TenantRole.VIEWER) return ResponseEntity.notFound().build()
        val tolerance = body.tolerance?.let { Tolerance(it.amount, it.days) } ?: Tolerance.EXACT
        val zone = ZoneId.of(body.zone)
        val records =
            body.records.map {
                SourceRecord(
                    it.sourceSystem,
                    it.externalId,
                    it.amount,
                    Currency.getInstance(it.currency),
                    it.date,
                )
            }
        val result =
            try {
                runner.run(body.tenantId, records, tolerance, zone, jwt.subject, UUID.randomUUID())
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().build()
            } catch (e: IllegalStateException) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
        return ResponseEntity.ok(
            RunView(
                runId = result.runId,
                matched = result.matched,
                breaks =
                    result.outcomes.map {
                        BreakView(
                            it.brk.kind.wireValue,
                            it.brk.sourceSystem,
                            it.brk.sourceRef,
                            it.brk.ledgerEventId,
                            it.brk.detail,
                            it.taskId,
                            it.opened,
                        )
                    },
            ),
        )
    }

    data class RecordBody(
        @field:NotBlank val sourceSystem: String,
        @field:NotBlank val externalId: String,
        val amount: BigDecimal,
        @field:NotBlank val currency: String,
        val date: LocalDate,
    )

    data class ToleranceBody(
        val amount: BigDecimal,
        val days: Int,
    )

    data class RunBody(
        val tenantId: UUID,
        @field:NotEmpty @field:Valid val records: List<RecordBody>,
        val tolerance: ToleranceBody? = null,
        val zone: String = "UTC",
    )

    data class BreakView(
        val kind: String,
        val sourceSystem: String,
        val sourceRef: String?,
        val ledgerEventId: UUID?,
        val detail: Map<String, String>,
        val taskId: UUID,
        val opened: Boolean,
    )

    data class RunView(
        val runId: UUID,
        val matched: Int,
        val breaks: List<BreakView>,
    )
}
