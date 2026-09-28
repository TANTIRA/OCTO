package com.octo.api.agents

import com.octo.api.agents.persistence.AgentRun
import com.octo.api.agents.persistence.AgentRunRecord
import com.octo.api.agents.persistence.AgentRunStatus
import com.octo.api.agents.persistence.AgentRuns
import com.octo.api.agents.persistence.JdbcAgentRunsStore
import com.octo.persistence.TenantScope
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.util.UUID
import javax.sql.DataSource

/**
 * Wires the sidecar client. `OCTO_AGENTS_BASE_URL` unset means no sidecar is deployed alongside —
 * the bean still exists (controllers inject it unconditionally) but fails on use with
 * [AgentsUnavailableException], which the edge maps to 503 rather than letting a misconfiguration
 * look like a client error.
 */
@Configuration(proxyBeanMethods = false)
class AgentsConfiguration {
    @Bean
    fun jdkAgentsClient(env: Environment): AgentsClient {
        val baseUrl =
            env.getProperty("OCTO_AGENTS_BASE_URL")?.takeIf(String::isNotBlank)
                ?: return AgentsClient { _, _ ->
                    throw AgentsUnavailableException(IllegalStateException("OCTO_AGENTS_BASE_URL is not configured"))
                }
        return JdkAgentsClient(baseUrl, env.getProperty("OCTO_AGENTS_TOKEN") ?: "")
    }

    /** `agent_run` (V33), behind the same lazy datasource boundary the access beans use. */
    @Bean
    fun jdbcAgentRuns(dataSource: ObjectProvider<DataSource>): AgentRuns {
        val store by lazy { JdbcAgentRunsStore(dataSource.getObject()) }
        return object : AgentRuns {
            override fun record(
                record: AgentRunRecord,
                scope: TenantScope,
            ) = store.record(record, scope)

            override fun finish(
                id: UUID,
                status: AgentRunStatus,
                output: String?,
                verdict: String?,
                error: String?,
                scope: TenantScope,
            ) = store.finish(id, status, output, verdict, error, scope)

            override fun recordOutcome(
                id: UUID,
                outcome: String,
                scope: TenantScope,
            ) = store.recordOutcome(id, outcome, scope)

            override fun loadByKey(
                tenantId: UUID,
                runKey: String,
                scope: TenantScope,
            ) = store.loadByKey(tenantId, runKey, scope)

            override fun load(
                id: UUID,
                scope: TenantScope,
            ) = store.load(id, scope)

            override fun list(
                tenantId: UUID,
                subjectType: String?,
                subjectId: String?,
                limit: Int,
                scope: TenantScope,
            ) = store.list(tenantId, subjectType, subjectId, limit, scope)
        }
    }
}
