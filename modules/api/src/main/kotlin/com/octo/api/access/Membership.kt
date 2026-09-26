package com.mesta.asset.api.access

import java.time.Instant
import java.util.UUID

/**
 * Coarse tenant-scoped roles (RBAC). data-security-governance.md scopes authorization by organization
 * and assigns the duties these map to; entity-level ABAC scoping (fund, deal, document, field) builds
 * on this and is a follow-up — see the V8 migration header.
 */
enum class TenantRole(
    val wireValue: String,
) {
    /** Tenant administration: membership, configuration. Segregated from the approval duties. */
    ADMIN("admin"),

    /** Working access: data entry, analysis, reconciliation. */
    ANALYST("analyst"),

    /** Approval gates: overrides, valuation and outbound-artifact decisions. */
    APPROVER("approver"),

    /** Read-only access, e.g. external LP users scoped to what the tenant publishes to them. */
    VIEWER("viewer"),
    ;

    companion object {
        fun fromWireValue(value: String): TenantRole = entries.first { it.wireValue == value }
    }
}

/** An organization whose data and users form one access boundary. Immutable once created (V8). */
data class Tenant(
    val id: UUID,
    val slug: String,
    val displayName: String,
)

/**
 * A fact about one user's access to one tenant. Membership state is never stored, only derived from
 * these events — like a task's state from `workflow_task_event`. `actor` is the grantor's user id.
 */
sealed interface MembershipEvent {
    val actor: String
    val at: Instant

    data class Granted(
        override val actor: String,
        override val at: Instant,
        val role: TenantRole,
    ) : MembershipEvent

    data class RoleChanged(
        override val actor: String,
        override val at: Instant,
        val role: TenantRole,
    ) : MembershipEvent

    data class Revoked(
        override val actor: String,
        override val at: Instant,
        val rationale: String,
    ) : MembershipEvent {
        init {
            require(rationale.isNotBlank()) { "a revocation needs a rationale" }
        }
    }
}

enum class MembershipStatus {
    /** Registered as a member but no grant event yet — no access. */
    NONE,
    ACTIVE,
    REVOKED,
}

data class MembershipState(
    val tenantId: UUID,
    val userId: UUID,
    val status: MembershipStatus,
    val role: TenantRole?,
    val lastEventAt: Instant,
) {
    val active: Boolean get() = status == MembershipStatus.ACTIVE
}

/** A freshly registered member: known to the tenant, holding no access until the first grant. */
fun registered(
    tenantId: UUID,
    userId: UUID,
    at: Instant,
): MembershipState = MembershipState(tenantId, userId, MembershipStatus.NONE, role = null, lastEventAt = at)

/** The state after [events], in order. Throws on any transition [next] rejects. */
fun replay(
    state: MembershipState,
    events: List<MembershipEvent>,
): MembershipState = events.fold(state) { s, e -> s.next(e) }

/**
 * Applies one event under the rules in `data-security-governance.md`: access starts with a grant and
 * ends with a revocation (a later grant may re-grant), and nobody grants or changes their own access.
 * The V8 trigger repeats these rules for callers that bypass this state machine.
 */
fun MembershipState.next(event: MembershipEvent): MembershipState {
    require(event.actor.isNotBlank()) { "every event needs an actor" }
    require(!event.at.isBefore(lastEventAt)) { "events must be in time order" }
    val after =
        when (event) {
            is MembershipEvent.Granted -> {
                require(status != MembershipStatus.ACTIVE) {
                    "user $userId already has access to tenant $tenantId; change the role instead"
                }
                require(event.actor != userId.toString()) {
                    "segregation of duties: $userId cannot grant their own access"
                }
                copy(status = MembershipStatus.ACTIVE, role = event.role)
            }
            is MembershipEvent.RoleChanged -> {
                require(status == MembershipStatus.ACTIVE) { "user $userId has no active membership in tenant $tenantId" }
                require(event.actor != userId.toString()) {
                    "segregation of duties: $userId cannot change their own role"
                }
                copy(role = event.role)
            }
            is MembershipEvent.Revoked -> {
                require(status == MembershipStatus.ACTIVE) { "user $userId has no active membership in tenant $tenantId" }
                copy(status = MembershipStatus.REVOKED, role = null)
            }
        }
    return after.copy(lastEventAt = event.at)
}
