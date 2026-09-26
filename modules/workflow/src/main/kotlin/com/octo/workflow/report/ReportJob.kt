package com.mesta.asset.workflow.report

import java.time.Instant
import java.util.UUID

/** The report types of #6 slice 7. Each maps to an engine in `analytics` or `lookthrough`. */
enum class ReportType(
    val wireValue: String,
) {
    PERFORMANCE("performance"),
    EXPOSURE("exposure"),
    ATTRIBUTION("attribution"),
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

/** One row of `mesta.report_job`. [result] is jsonb object text; present exactly when [status] is DONE. */
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
)

/** The job store the endpoints and the runner depend on. */
interface ReportJobs {
    fun submit(request: ReportRequest): ReportJob

    fun load(id: UUID): ReportJob?

    /** Moves the oldest `new` job to `executing` and returns it, or null when the queue is empty. Two runners never claim the same job. */
    fun claimNext(): ReportJob?

    fun complete(
        id: UUID,
        result: String,
        artifactSha256: String? = null,
    ): ReportJob

    fun fail(
        id: UUID,
        error: String,
    ): ReportJob

    /** Attaches the approval task that gates outbound release; V13 allows it once, after `done`. */
    fun attachApproval(
        id: UUID,
        taskId: UUID,
    ): ReportJob
}
