package com.octo.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.api.agents.AgentsClient
import com.octo.persistence.TenantScope
import com.octo.workflow.Task
import com.octo.workflow.persistence.JdbcTaskStore
import com.octo.workflow.persistence.TaskProvenance
import com.octo.workflow.report.JdbcReportJobStore
import com.octo.workflow.report.JdbcReportScheduleStore
import com.octo.workflow.report.ReportJob
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportRequest
import com.octo.workflow.report.ReportSchedule
import com.octo.workflow.report.ReportSchedules
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.UUID
import javax.sql.DataSource

/** Wires the report store lazily like `AccessConfiguration`; the poller runs unless `octo.reports.poll` is false. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ReportConfiguration {
    @Bean
    fun jdbcReportJobs(dataSource: ObjectProvider<DataSource>): ReportJobs {
        val store by lazy { JdbcReportJobStore(dataSource.getObject()) }
        return object : ReportJobs {
            override fun submit(
                request: ReportRequest,
                scope: TenantScope,
            ) = store.submit(request, scope)

            override fun load(
                id: UUID,
                scope: TenantScope,
            ) = store.load(id, scope)

            override fun pendingCount(
                tenantId: UUID,
                scope: TenantScope,
            ) = store.pendingCount(tenantId, scope)

            override fun claimNext() = store.claimNext()

            override fun renew(
                id: UUID,
                claimToken: UUID,
            ) = store.renew(id, claimToken)

            override fun complete(
                id: UUID,
                claimToken: UUID,
                result: String,
                artifactSha256: String?,
            ) = store.complete(id, claimToken, result, artifactSha256)

            override fun fail(
                id: UUID,
                claimToken: UUID,
                error: String,
            ): ReportJob = store.fail(id, claimToken, error)

            override fun attachApproval(
                id: UUID,
                taskId: UUID,
            ): ReportJob = store.attachApproval(id, taskId)
        }
    }

    @Bean
    fun jdbcReleaseTasks(dataSource: ObjectProvider<DataSource>): ReleaseTasks {
        val store by lazy { JdbcTaskStore(dataSource.getObject()) }
        return object : ReleaseTasks {
            override fun openUnlessOpen(
                task: Task,
                provenance: TaskProvenance,
            ) = store.openUnlessOpen(task, provenance)

            override fun state(taskId: UUID) = store.load(taskId)
        }
    }

    @Bean
    fun jdbcReportSchedules(dataSource: ObjectProvider<DataSource>): ReportSchedules {
        val store by lazy { JdbcReportScheduleStore(dataSource.getObject()) }
        return object : ReportSchedules {
            override fun upsert(
                schedule: ReportSchedule,
                scope: TenantScope,
            ) = store.upsert(schedule, scope)

            override fun load(
                id: UUID,
                scope: TenantScope,
            ) = store.load(id, scope)

            override fun list(
                tenantId: UUID,
                scope: TenantScope,
            ) = store.list(tenantId, scope)

            override fun claimDue(
                now: java.time.Instant,
                lease: java.time.Duration,
            ) = store.claimDue(now, lease)

            override fun markRun(
                id: UUID,
                nextRunAt: java.time.Instant,
            ) = store.markRun(id, nextRunAt)
        }
    }

    @Bean
    @ConditionalOnProperty("octo.reports.poll", havingValue = "true", matchIfMissing = true)
    fun reportRunner(
        jobs: ReportJobs,
        json: ObjectMapper,
        agents: AgentsClient,
    ) = ReportRunner(jobs, json, agents)

    @Bean
    @ConditionalOnProperty("octo.reports.schedules.poll", havingValue = "true", matchIfMissing = true)
    fun reportScheduleRunner(
        schedules: ReportSchedules,
        jobs: ReportJobs,
    ) = ReportScheduleRunner(schedules, jobs)
}
