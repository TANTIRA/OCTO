package com.octo.dealsourcing.persistence

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.persistence.TenantScope
import java.time.Instant
import java.util.UUID

/** One import call registers at most this many prospects — adapters page larger syncs themselves. */
const val IMPORT_BATCH_LIMIT = 500

/** One pipeline page returns at most this many prospects — callers page larger stages themselves. */
const val PIPELINE_PAGE_LIMIT = 500

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
     * already exists is a no-op, not a duplicate. Returns the ids actually inserted. The store
     * enforces [IMPORT_BATCH_LIMIT] itself so a caller outside the api edge can still never hold
     * an unbounded transaction open.
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

    /**
     * Up to [limit] prospects of the tenant currently standing at [stage], newest registrations
     * first, after skipping [offset] — a bounded page, never the whole stage.
     * first, after skipping [offset] — a bounded page, never the whole stage. The store enforces
     * [PIPELINE_PAGE_LIMIT] itself so a caller outside the api edge can still never hold an
     * unbounded read open.
     */
    fun listAtStage(
        tenantId: UUID,
        stage: ProspectStage,
        limit: Int,
        offset: Int,
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
