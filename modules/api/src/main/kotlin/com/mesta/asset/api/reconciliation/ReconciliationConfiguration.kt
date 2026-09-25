package com.mesta.asset.api.reconciliation

import com.mesta.asset.recon.matching.Break
import com.mesta.asset.recon.matching.persistence.JdbcReconciliationStore
import com.mesta.asset.recon.matching.persistence.ReconciliationStore
import com.mesta.asset.workflow.persistence.JdbcTaskStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/** Wires the reconciliation store and runner lazily, like `AccessConfiguration`, so a context without a datasource still starts. */
@Configuration(proxyBeanMethods = false)
class ReconciliationConfiguration {
    @Bean
    fun jdbcReconciliationStore(dataSource: ObjectProvider<DataSource>): ReconciliationStore {
        val store by lazy { JdbcReconciliationStore(dataSource.getObject()) }
        return object : ReconciliationStore {
            override fun iborRecords(
                sourceSystem: String,
                zone: ZoneId,
            ) = store.iborRecords(sourceSystem, zone)

            override fun existingTask(
                tenantId: UUID,
                brk: Break,
            ) = store.existingTask(tenantId, brk)

            override fun record(
                tenantId: UUID,
                runId: UUID,
                brk: Break,
                taskId: UUID?,
                correlationId: UUID,
            ) = store.record(tenantId, runId, brk, taskId, correlationId)
        }
    }

    @Bean
    fun jdbcBreakTaskOpener(dataSource: ObjectProvider<DataSource>): BreakTaskOpener {
        val tasks by lazy { JdbcTaskStore(dataSource.getObject()) }
        return BreakTaskOpener { task, provenance -> tasks.create(task, provenance) }
    }

    @Bean
    fun reconciliationRunner(
        store: ReconciliationStore,
        tasks: BreakTaskOpener,
    ) = ReconciliationRunner(store, tasks)
}
