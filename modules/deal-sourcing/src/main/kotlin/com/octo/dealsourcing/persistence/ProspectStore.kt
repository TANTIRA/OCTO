package com.octo.dealsourcing.persistence

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.persistence.TenantScope
import java.time.Instant
import java.util.UUID

/** One stored event row — the audit trail view: seq ordering, actor, rationale, provenance, IC task. */
data class ProspectEventRow(
    val seq: Long,
    val eventType: String,
    val stageFrom: ProspectStage?,
    val stageTo: ProspectStage?,
    val actor: String,
    val rationale: String?,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val correlationId: UUID,
    val taskId: UUID?,
)

/** The prospect pipeline contract services bind against; `JdbcProspectStore` behind it in production. */
interface ProspectStore {
    /** Registers [prospect]; the row itself is the registration fact. */
    fun create(
        prospect: Prospect,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    )

    /**
     * Bulk registration for source adapters (CRM sync): a `(tenant, source, source_ref)` that
     * already exists is a no-op, not a duplicate. Returns the ids actually inserted.
     */
    fun importBatch(
        prospects: List<Prospect>,
        actor: String,
        provenance: ProspectProvenance,
        scope: TenantScope,
    ): List<UUID>

    /** The prospect's replayed state, or null when no prospect has that id. */
    fun load(
        id: UUID,
        scope: TenantScope,
    ): ProspectState?

    /**
     * The prospect's full event history in append order, or null when no prospect has that id.
     * Audit reads need the rows themselves — actor, rationale, provenance — not just the state
     * they replay to.
     */
    fun history(
        id: UUID,
        scope: TenantScope,
    ): List<ProspectEventRow>?

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
