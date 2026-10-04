package com.octo.api.graph

import com.octo.api.access.PlatformAdmin
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * `GET /api/v1/admin/graph/reconciliation?tenantId=` (#564). Platform admins (`OCTO_PLATFORM_ADMINS`) only.
 * The read uses the same disposition as the schedule: each discrepancy opens one `evidence-request`, deduplicated
 * with `openUnlessOpen`. When `NEO4J_URI` is unset the run is absent and the route answers 503 — never a fake
 * clean report.
 */
@RestController
class GraphReconciliationController(
    private val runs: ObjectProvider<GraphReconciliationRuns>,
    private val tenants: ObjectProvider<GraphTenantDirectory>,
    private val platform: PlatformAdmin,
) {
    @GetMapping("/api/v1/admin/graph/reconciliation")
    fun reconciliation(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam tenantId: UUID,
    ): ReconciliationView {
        val subject = jwt.subject
        if (subject == null || !platform.isAdmin(subject)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "graph reconciliation needs a platform admin")
        }
        val run = runs.ifAvailable
        val directory = tenants.ifAvailable
        if (run == null || directory == null) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "graph reconciliation is not configured")
        }
        if (!directory.exists(tenantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such tenant")
        }
        val report = run.run(tenantId, UUID.randomUUID())
        return ReconciliationView(
            tenantId = report.reconciliation.tenantId,
            checked = report.reconciliation.checked,
            clean = report.reconciliation.clean,
            discrepancies =
                report.tasks.map { drift ->
                    DiscrepancyView(
                        drift.discrepancy.kind.wireValue,
                        drift.discrepancy.aggregateType,
                        drift.discrepancy.octoId,
                        drift.discrepancy.detail,
                        drift.taskId,
                        drift.opened,
                    )
                },
        )
    }

    data class DiscrepancyView(
        val kind: String,
        val aggregateType: String,
        val octoId: UUID,
        val detail: String,
        val taskId: UUID,
        val opened: Boolean,
    )

    data class ReconciliationView(
        val tenantId: UUID,
        val checked: Int,
        val clean: Boolean,
        val discrepancies: List<DiscrepancyView>,
    )
}
