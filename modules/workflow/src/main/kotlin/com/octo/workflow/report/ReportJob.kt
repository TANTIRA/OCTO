package com.octo.workflow.report

import com.octo.persistence.TenantScope
import java.time.Instant
import java.util.UUID

/**
 * The report types of #6 slice 7. Each maps to an engine in `analytics` or `lookthrough`. Every wire value
 * must be in report_job's `report_job_type_known` check, and every [schedulable] one in report_schedule's
 * `report_schedule_type_known` — ReportJobMigrationIT enforces both (#488).
 */
enum class ReportType(
    val wireValue: String,
    /** False for types whose input is the current event set: a schedule's fixed template would replay stale data. */
    val schedulable: Boolean = true,
) {
    PERFORMANCE("performance"),
    EXPOSURE("exposure"),
    ATTRIBUTION("attribution"),
    GL_EXPORT("gl-export", schedulable = false),

    /**
     * Agent-drafted LP letter — the sidecar narrates the job's own inline parameters; release still gates it.
     * On-demand only: a schedule would re-narrate the same figures every period.
     */
    LP_REPORT("lp-report", schedulable = false),
    ;

    companion object {
        fun fromWireValue(value: String) = entries.first { it.wireValue == value }
    }
}

/** `new -> executing -> done | error`, once; V13's trigger enforces it. */
enum class JobStatus(
    val wireValue: String,
    val terminal: Boolean,
) {
    NEW("new", false),
    EXECUTING("executing", false),
    DONE("done", true),
    ERROR("error", true),
    ;

    companion object {
        fun fromWireValue(value: String) = entries.first { it.wireValue == value }
    }
}

/** A tenant's queue may hold at most this many `new`/`executing` jobs — beyond it `POST /api/v1/reports` answers 429. */
const val PENDING_REPORT_LIMIT = 100

/** What a caller submits (Marquee: type, positionSourceType, measures). [parameters] is jsonb object text the engine adapter reads. */
data class ReportRequest(
    val tenantId: UUID,
    val type: ReportType,
    val positionSourceType: String,
    val positionSourceId: String,
    val measures: List<String>,
    val parameters: String,
    val requestedBy: String,
    val correlationId: UUID,
) {
    init {
        require(positionSourceType.isNotBlank() && positionSourceId.isNotBlank()) { "a report names its position source" }
        require(measures.none { it.isBlank() }) { "measures must not be blank" }
        require(requestedBy.isNotBlank()) { "a report names who requested it" }
    }
}

/** One row of `octo.report_job`. [result] is jsonb object text; present exactly when [status] is DONE. */
data class ReportJob(
    val id: UUID,
    val request: ReportRequest,
    val status: JobStatus,
    val result: String?,
    val error: String?,
    val artifactSha256: String?,
    val approvalTaskId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val claimToken: UUID? = null,
    val claimedUntil: Instant? = null,
)

/** The job store the endpoints and the runner depend on. */
interface ReportJobs {
    fun submit(
        request: ReportRequest,
        scope: TenantScope,
    ): ReportJob

    fun load(
        id: UUID,
        scope: TenantScope,
    ): ReportJob?

    /** How many of the tenant's jobs sit in `new` or `executing` — the queue depth [PENDING_REPORT_LIMIT] bounds. */
    fun pendingCount(
        tenantId: UUID,
        scope: TenantScope,
    ): Int

    /** Moves the oldest `new` job to `executing` and returns it, or null when the queue is empty. Two runners never claim the same job. */
    fun claimNext(): ReportJob?

    fun renew(
        id: UUID,
        claimToken: UUID,
    ): Boolean

    fun complete(
        id: UUID,
        claimToken: UUID,
        result: String,
        artifactSha256: String? = null,
    ): ReportJob

    fun fail(
        id: UUID,
        claimToken: UUID,
        error: String,
    ): ReportJob

    /** Attaches the approval task that gates outbound release; V13 allows it once, after `done`. */
    fun attachApproval(
        id: UUID,
        taskId: UUID,
    ): ReportJob
}
