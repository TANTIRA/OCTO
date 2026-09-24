package com.mesta.asset.api.access

import java.util.UUID

/** What one active membership gives a user: the tenant and the role they hold in it. */
data class TenantAccess(
    val tenantId: UUID,
    val slug: String,
    val role: TenantRole,
)

/**
 * Resolves the tenants a user can currently act in — the directory the auth boundary and tenant-scoped
 * services consult. A user absent from every tenant simply gets an empty list: default is deny
 * (data-security-governance.md), so unknown users hold no access rather than an error that leaks state.
 */
fun interface TenantDirectory {
    fun tenantsOf(userId: UUID): List<TenantAccess>
}
