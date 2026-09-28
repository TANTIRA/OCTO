package com.octo.api.agents.persistence

import com.octo.api.access.persistence.AccessProvenance
import com.octo.persistence.TenantScope
import java.time.Instant
import java.util.UUID

/** The life phases of a recorded run — `running` is open, the rest are terminal. */
enum class AgentRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    REFUSED,
    ;

    val wireValue: String get() = name.lowercase().replace('_', '-')
}

/**
 * What a run records at open ([AgentRuns.record]): every field the verdict later joins so the
 * row alone can replay the decision. JSON payloads stay text — the sidecar owns their shape and
 * the edge stores them verbatim, so a schema change there never forces one here.
 */
data class AgentRunRecord(
    val tenantId: UUID,
    val workflow: String,
    val runKey: String,
    val subjectType: String,
    val subjectId: String,
    val actor: String,
    val input: String,
    val models: String,
    val thresholds: String?,
    val requestIds: String?,
    val provenance: AccessProvenance,
)

/** A stored run, terminal or in flight. */
data class AgentRun(
    val id: UUID,
    val tenantId: UUID,
    val workflow: String,
    val runKey: String,
    val subjectType: String,
    val subjectId: String,
    val status: AgentRunStatus,
    val actor: String,
    val input: String,
    val output: String?,
    val verdict: String?,
    val models: String,
    val thresholds: String?,
    val requestIds: String?,
    val error: String?,
    val humanOutcome: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

/**
 * `octo.agent_run` (V33) — the audited spine of F4. Two writes per run: [record] opens `running`,
 * [finish] lands the verdict/output/error and closes it. [loadByKey] is the idempotency read —
 * a retried trigger finds its row instead of duplicating. An interface like [TenantSettings] so
 * wiring stays lazy and a datasource-less context boots.
 */
interface AgentRuns {
    /** Opens a run as `running` and returns its id, or null when [record.runKey] already ran. */
    fun record(
        record: AgentRunRecord,
        scope: TenantScope,
    ): UUID?

    /**
     * Closes a `running` row with its terminal [status] plus the verdict, output and error the
     * run produced. Returns false when the row is not in `running` (wrong tenant → RLS also
     * answers false, never leaks existence).
     */
    fun finish(
        id: UUID,
        status: AgentRunStatus,
        output: String?,
        verdict: String?,
        error: String?,
        scope: TenantScope,
    ): Boolean

    /**
     * Lands the human decision on a finished run — F12's verdict-vs-reality comparison reads this
     * column. Idempotent: the first outcome wins, later calls report false rather than rewriting
     * history.
     */
    fun recordOutcome(
        id: UUID,
        outcome: String,
        scope: TenantScope,
    ): Boolean

    /** The row a caller-supplied [runKey] recorded, or null — the retry path's read. */
    fun loadByKey(
        tenantId: UUID,
        runKey: String,
        scope: TenantScope,
    ): AgentRun?

    /** A single run by id inside the caller's tenant scope. */
    fun load(
        id: UUID,
        scope: TenantScope,
    ): AgentRun?

    /** Runs for a subject in [tenantId]'s boundary, newest first — F12's query surface. */
    fun list(
        tenantId: UUID,
        subjectType: String?,
        subjectId: String?,
        limit: Int,
        scope: TenantScope,
    ): List<AgentRun>
}
