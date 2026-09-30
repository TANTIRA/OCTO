package com.octo.api.access.persistence

import com.octo.api.access.MembershipEvent
import com.octo.api.access.MembershipState
import com.octo.api.access.Tenant
import java.time.Instant
import java.util.UUID

/**
 * The write side of the access store, exposed to the admin edge the same way [TenantDirectory]
 * exposes the read side: an interface so the wiring stays lazy and a context without a datasource
 * still boots (denying at use, not at startup).
 */
interface AccessAdministration {
    /**
     * Provisions a tenant and its first administrator in one transaction: the tenant row, the
     * member registration, and the granting event all commit together or not at all.
     */
    fun provisionTenant(
        tenant: Tenant,
        adminUserId: UUID,
        grantor: String,
        registeredAt: Instant,
        provenance: AccessProvenance,
    ): MembershipState

    /** Registers [userId] in the tenant; access itself still needs a granted event. */
    fun registerMember(
        tenantId: UUID,
        userId: UUID,
        registeredAt: Instant,
        provenance: AccessProvenance,
    )

    /** The membership's replayed state, or null when the pair is unregistered. */
    fun load(
        tenantId: UUID,
        userId: UUID,
    ): MembershipState?

    /**
     * Validates [event] against the replayed state and stores it — throws [NoSuchElementException]
     * for an unregistered pair and [IllegalArgumentException] for a rejected transition.
     */
    fun append(
        tenantId: UUID,
        userId: UUID,
        event: MembershipEvent,
        provenance: AccessProvenance,
    ): MembershipState
}
