package com.mesta.asset.api.compliance

import com.mesta.asset.recon.compliance.ComplianceRule
import com.mesta.asset.recon.compliance.Evaluation
import com.mesta.asset.recon.compliance.persistence.ComplianceProvenance
import com.mesta.asset.recon.compliance.persistence.ComplianceStore
import com.mesta.asset.recon.compliance.persistence.JdbcComplianceStore
import com.mesta.asset.workflow.persistence.JdbcTaskStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import javax.sql.DataSource

/** Wires the compliance store and the task opener lazily, like `AccessConfiguration`, so a context without a datasource still starts. */
@Configuration(proxyBeanMethods = false)
class ComplianceConfiguration {
    @Bean
    fun jdbcComplianceStore(dataSource: ObjectProvider<DataSource>): ComplianceStore {
        val store by lazy { JdbcComplianceStore(dataSource.getObject()) }
        return object : ComplianceStore {
            override fun activeRules(tenantId: UUID) = store.activeRules(tenantId)

            override fun defineRule(
                tenantId: UUID,
                rule: ComplianceRule,
                provenance: ComplianceProvenance,
            ) = store.defineRule(tenantId, rule, provenance)

            override fun breachTask(
                tenantId: UUID,
                evaluation: Evaluation,
            ) = store.breachTask(tenantId, evaluation)

            override fun record(
                tenantId: UUID,
                evaluation: Evaluation,
                taskId: UUID?,
                correlationId: UUID,
            ) = store.record(tenantId, evaluation, taskId, correlationId)
        }
    }

    @Bean
    fun complianceTaskOpener(dataSource: ObjectProvider<DataSource>): TaskOpener {
        val store by lazy { JdbcTaskStore(dataSource.getObject()) }
        return TaskOpener { task, provenance -> store.create(task, provenance) }
    }

    @Bean
    fun complianceRunner(
        store: ComplianceStore,
        tasks: TaskOpener,
    ) = ComplianceRunner(store, tasks)
}
