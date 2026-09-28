package com.octo.api.access.persistence

import com.octo.persistence.TenantScope
import java.util.UUID

/**
 * Per-tenant configuration (`octo.tenant_setting`, V31) — the read/write surface the runners and
 * the admin edge share. Values are opaque JSON text; the caller that understands a key parses it.
 * Like [TenantDirectory], an interface so wiring stays lazy and a datasource-less context boots.
 */
interface TenantSettings {
    /** The current value of [key] in [tenantId]'s boundary, or null when unset. */
    fun get(
        tenantId: UUID,
        key: String,
        scope: TenantScope,
    ): String?

    /** Every setting in [tenantId]'s boundary, key to JSON text. */
    fun all(
        tenantId: UUID,
        scope: TenantScope,
    ): Map<String, String>

    /**
     * Upserts [key] to [value] (JSON text) in [tenantId] and appends the matching audit_event row
     * in the same transaction — a privileged configuration change is always audited.
     */
    fun put(
        tenantId: UUID,
        key: String,
        value: String,
        actor: String,
        provenance: AccessProvenance,
        scope: TenantScope,
    )
}

/** The keys the platform reads — one registry so a setting name never forks across modules. */
object TenantSettingKeys {
    /** Per-tenant override of the request rate limit; absent means the env default. */
    const val RATE_LIMIT_PER_MINUTE = "rate_limit_per_minute"
}
