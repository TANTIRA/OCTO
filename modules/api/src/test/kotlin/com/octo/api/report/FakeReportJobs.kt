package com.octo.api.report

import com.octo.persistence.TenantScope
import com.octo.workflow.report.JobStatus
import com.octo.workflow.report.ReportJob
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportRequest
import java.time.Instant
import java.util.UUID

/** In-memory `ReportJobs` with V13's transition rule, for the endpoint and runner tests. */
class FakeReportJobs : ReportJobs {
    val jobs = linkedMapOf<UUID, ReportJob>()

    override fun submit(
        request: ReportRequest,
        scope: TenantScope,
    ): ReportJob {
        val now = Instant.now()
        return ReportJob(UUID.randomUUID(), request, JobStatus.NEW, null, null, null, null, now, now).also { jobs[it.id] = it }
    }

    override fun load(
        id: UUID,
        scope: TenantScope,
    ) = jobs[id]

    override fun pendingCount(
        tenantId: UUID,
        scope: TenantScope,
    ) = jobs.values.count { it.request.tenantId == tenantId && !it.status.terminal }

    override fun claimNext() =
        jobs.values.firstOrNull { it.status == JobStatus.NEW }?.let {
            move(it.id, JobStatus.EXECUTING) { job ->
                job.copy(claimToken = UUID.randomUUID(), claimedUntil = Instant.now().plusSeconds(300))
            }
        }

    override fun renew(
        id: UUID,
        claimToken: UUID,
    ): Boolean {
        val job = jobs.getValue(id)
        if (job.claimToken != claimToken || job.status != JobStatus.EXECUTING) return false
        jobs[id] = job.copy(claimedUntil = Instant.now().plusSeconds(300))
        return true
    }

    override fun complete(
        id: UUID,
        claimToken: UUID,
        result: String,
        artifactSha256: String?,
    ) = move(id, JobStatus.DONE) {
        check(it.claimToken == claimToken)
        it.copy(result = result, artifactSha256 = artifactSha256, claimToken = null, claimedUntil = null)
    }

    override fun fail(
        id: UUID,
        claimToken: UUID,
        error: String,
    ) = move(id, JobStatus.ERROR) {
        check(it.claimToken == claimToken)
        it.copy(error = error, claimToken = null, claimedUntil = null)
    }

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
