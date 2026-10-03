package com.octo.api.graph

import com.octo.api.access.PlatformAdmin
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** One discrepancy as the admin endpoint reports it — the wire form of [GraphDiscrepancy]. */
data class GraphDiscrepancyView(
    val kind: String,
    val aggregateType: String,
    val octoId: UUID,
    val detail: String,
)

/** One tenant's graph-vs-ledger comparison, fresh from [GraphReconciliationRunner.reconcileTenant]. */
data class GraphReconciliationView(
    val tenantId: UUID,
    val checked: Int,
    val clean: Boolean,
    val discrepancies: List<GraphDiscrepancyView>,
)

private fun GraphReconciliation.view() =
    GraphReconciliationView(
        tenantId,
        checked,
        clean,
        discrepancies.map { GraphDiscrepancyView(it.kind.wireValue, it.aggregateType, it.octoId, it.detail) },
    )

/**
 * `GET /api/v1/admin/graph/reconciliation?tenantId=…` — a live reconciliation for one tenant,
 * platform admins only (#564). The call is the same disposition the scheduled pass gives drift:
 * reading the report also opens the deduplicated evidence-request tasks, so an admin never sees
 * a discrepancy the workflow does not know about. Registered only when `NEO4J_URI` is set — with
 * no graph there is nothing to reconcile — see [GraphConfiguration].
 */
@RestController
class GraphReconciliationController(
    private val runner: GraphReconciliationRunner,
    private val platform: PlatformAdmin,
) {
    @GetMapping("/api/v1/admin/graph/reconciliation")
    fun report(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam tenantId: UUID,
    ): GraphReconciliationView {
        if (!platform.isAdmin(jwt.subject)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "graph reconciliation needs a platform admin")
        }
        return runner.reconcileTenant(tenantId).view()
    }
}
