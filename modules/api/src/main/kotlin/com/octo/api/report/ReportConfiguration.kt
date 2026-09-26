package com.octo.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.octo.workflow.Task
import com.octo.workflow.persistence.JdbcTaskStore
import com.octo.workflow.persistence.TaskProvenance
import com.octo.workflow.report.JdbcReportJobStore
import com.octo.workflow.report.ReportJob
import com.octo.workflow.report.ReportJobs
import com.octo.workflow.report.ReportRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.UUID
import javax.sql.DataSource

/** Wires the report store lazily like `AccessConfiguration`; the poller runs unless `mesta.reports.poll` is false. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ReportConfiguration {
    @Bean
    fun jdbcReportJobs(dataSource: ObjectProvider<DataSource>): ReportJobs {
        val store by lazy { JdbcReportJobStore(dataSource.getObject()) }
        return object : ReportJobs {
            override fun submit(request: ReportRequest) = store.submit(request)

            override fun load(id: UUID) = store.load(id)

            override fun claimNext() = store.claimNext()

            override fun complete(
                id: UUID,
                result: String,
                artifactSha256: String?,
            ) = store.complete(id, result, artifactSha256)

            override fun fail(
                id: UUID,
                error: String,
            ): ReportJob = store.fail(id, error)

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
            override fun open(
                task: Task,
                provenance: TaskProvenance,
            ) = store.create(task, provenance)

            override fun state(taskId: UUID) = store.load(taskId)
        }
    }

    @Bean
    @ConditionalOnProperty("mesta.reports.poll", havingValue = "true", matchIfMissing = true)
    fun reportRunner(
        jobs: ReportJobs,
        json: ObjectMapper,
    ) = ReportRunner(jobs, json)
}
