package com.mesta.asset.api.report

import com.mesta.asset.workflow.report.JobStatus
import com.mesta.asset.workflow.report.ReportJob
import com.mesta.asset.workflow.report.ReportJobs
import com.mesta.asset.workflow.report.ReportRequest
import java.time.Instant
import java.util.UUID

/** In-memory `ReportJobs` with V13's transition rule, for the endpoint and runner tests. */
class FakeReportJobs : ReportJobs {
    val jobs = linkedMapOf<UUID, ReportJob>()

    override fun submit(request: ReportRequest): ReportJob {
        val now = Instant.now()
        return ReportJob(UUID.randomUUID(), request, JobStatus.NEW, null, null, null, null, now, now).also { jobs[it.id] = it }
    }

    override fun load(id: UUID) = jobs[id]

    override fun claimNext() = jobs.values.firstOrNull { it.status == JobStatus.NEW }?.let { move(it.id, JobStatus.EXECUTING) }

    override fun complete(
        id: UUID,
        result: String,
        artifactSha256: String?,
    ) = move(id, JobStatus.DONE) { it.copy(result = result, artifactSha256 = artifactSha256) }

    override fun fail(
        id: UUID,
        error: String,
    ) = move(id, JobStatus.ERROR) { it.copy(error = error) }

    override fun attachApproval(
        id: UUID,
        taskId: UUID,
    ): ReportJob {
        val job = jobs.getValue(id)
        check(job.status == JobStatus.DONE && job.approvalTaskId == null) { "one approval task, after done" }
        return job.copy(approvalTaskId = taskId).also { jobs[id] = it }
    }

    private fun move(
        id: UUID,
        to: JobStatus,
        change: (ReportJob) -> ReportJob = { it },
    ): ReportJob {
        val job = jobs.getValue(id)
        check(if (to == JobStatus.EXECUTING) job.status == JobStatus.NEW else job.status == JobStatus.EXECUTING) { "${job.status} -> $to" }
        return change(job.copy(status = to, updatedAt = Instant.now())).also { jobs[id] = it }
    }
}
