package com.octo.dealsourcing.persistence

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.TenantScope
import java.util.UUID

/** The prospect pipeline contract services bind against; `JdbcProspectStore` behind it in production. */
interface ProspectStore {
    /** Registers [prospect]; the row itself is the registration fact. */
    fun create(
        prospect: Prospect,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    )

    /** The prospect's replayed state, or null when no prospect has that id. */
    fun load(
        id: UUID,
        scope: TenantScope,
    ): ProspectState?

    /** Every prospect of the tenant currently standing at [stage]. */
    fun listAtStage(
        tenantId: UUID,
        stage: ProspectStage,
        scope: TenantScope,
    ): List<ProspectState>

    /**
     * Validates [event] against the replayed state and appends it. Throws [IllegalArgumentException]
     * for a transition the state machine rejects, or [NoSuchElementException] for an unknown prospect.
     */
    fun append(
        prospectId: UUID,
        event: ProspectEvent,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ): ProspectState
}
