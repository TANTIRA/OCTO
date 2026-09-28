package com.octo.api.access.persistence

import java.util.UUID

/**
 * Where a tenant's domain data lives (V32, ADR-0007). `key` is null for pool tenants — the shared
 * schema serves them — and names the `OCTO_DS_<KEY>` connection for bridge/silo placements.
 */
data class TenantPlacement(
    val tenantId: UUID,
    val isolationTier: String,
    val datasourceKey: String?,
)

/** Reads `tenant.isolation_tier`/`datasource_key` — the registry the routing seam consults. */
fun interface TenantPlacements {
    fun placementOf(tenantId: UUID): TenantPlacement?
}
