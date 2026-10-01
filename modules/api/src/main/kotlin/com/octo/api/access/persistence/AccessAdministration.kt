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

    /**
     * Grants [event]'s role, registering [userId] first when the pair is unknown — one transaction,
     * so a grant the state machine rejects ([IllegalArgumentException]) leaves no registration behind.
     */
    fun grant(
        tenantId: UUID,
        userId: UUID,
        event: MembershipEvent.Granted,
        provenance: AccessProvenance,
    ): MembershipState

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
