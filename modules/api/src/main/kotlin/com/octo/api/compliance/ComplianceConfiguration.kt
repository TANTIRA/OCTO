package com.octo.api.compliance

import com.octo.persistence.TenantScope
import com.octo.recon.compliance.Evaluation
import com.octo.recon.compliance.persistence.ComplianceProvenance
import com.octo.recon.compliance.persistence.ComplianceStore
import com.octo.recon.compliance.persistence.JdbcComplianceStore
import com.octo.workflow.persistence.JdbcTaskStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Wires the compliance store and the task opener lazily, like `AccessConfiguration`, so a context without a datasource still starts. */
@Configuration(proxyBeanMethods = false)
class ComplianceConfiguration {
    @Bean
    fun jdbcComplianceStore(dataSource: ObjectProvider<DataSource>): ComplianceStore {
        val store by lazy { JdbcComplianceStore(dataSource.getObject()) }
        return object : ComplianceStore {
            override fun activeRules(
                tenantId: UUID,
                scope: TenantScope,
            ) = store.activeRules(tenantId, scope)

            override fun defineRule(
                tenantId: UUID,
                ruleId: String,
                name: String,
                check: com.octo.recon.compliance.ComplianceCheck,
                expectedVersion: Int?,
                provenance: ComplianceProvenance,
                scope: TenantScope,
            ) = store.defineRule(tenantId, ruleId, name, check, expectedVersion, provenance, scope)

            override fun retire(
                tenantId: UUID,
                ruleId: String,
                provenance: ComplianceProvenance,
                scope: TenantScope,
            ) = store.retire(tenantId, ruleId, provenance, scope)

            override fun breachTask(
                tenantId: UUID,
                evaluation: Evaluation,
                scope: TenantScope,
            ) = store.breachTask(tenantId, evaluation, scope)

            override fun record(
                tenantId: UUID,
                evaluation: Evaluation,
                taskId: UUID?,
                correlationId: UUID,
                scope: TenantScope,
                openTask: ((Connection) -> Unit)?,
            ) = store.record(tenantId, evaluation, taskId, correlationId, scope, openTask)
        }
    }

    @Bean
    fun complianceTaskOpener(dataSource: ObjectProvider<DataSource>): TaskOpener {
        val store by lazy { JdbcTaskStore(dataSource.getObject()) }
        return TaskOpener { connection, task, provenance -> store.create(connection, task, provenance) }
    }

    @Bean
    fun complianceRunner(
        store: ComplianceStore,
        tasks: TaskOpener,
    ) = ComplianceRunner(store, tasks)
}
