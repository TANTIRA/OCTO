package com.octo.api.prospect

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.TenantScope
import com.octo.dealsourcing.persistence.JdbcProspectStore
import com.octo.dealsourcing.persistence.JdbcScreeningRuleStore
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.dealsourcing.persistence.ProspectStore
import com.octo.dealsourcing.persistence.ScreeningRuleRow
import com.octo.persistence.TenantScope
import com.octo.workflow.Task
import com.octo.workflow.TaskEvent
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.dealsourcing.persistence.ProspectStore
import com.octo.workflow.Task
import com.octo.workflow.TaskState
import com.octo.workflow.persistence.JdbcTaskStore
import com.octo.workflow.persistence.TaskProvenance
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import javax.sql.DataSource

/** Wires the prospect store like `AccessConfiguration`: resolved lazily so a context without a datasource still starts. */
@Configuration(proxyBeanMethods = false)
class ProspectConfiguration {
    @Bean
    fun jdbcProspectStore(dataSource: ObjectProvider<DataSource>): ProspectStore {
        val store by lazy { JdbcProspectStore(dataSource.getObject()) }
        return object : ProspectStore {
            override fun create(
                prospect: Prospect,
                actor: String,
                provenance: ProspectProvenance,
                scope: TenantScope,
            ) = store.create(prospect, actor, provenance, scope)

            override fun importBatch(
                prospects: List<Prospect>,
                actor: String,
                provenance: ProspectProvenance,
                scope: TenantScope,
            ) = store.importBatch(prospects, actor, provenance, scope)

            override fun load(
                id: UUID,
                scope: TenantScope,
            ) = store.load(id, scope)

            override fun history(
                id: UUID,
                scope: TenantScope,
            ) = store.history(id, scope)

            override fun listAtStage(
                tenantId: UUID,
                stage: ProspectStage,
                limit: Int,
                offset: Int,
                scope: TenantScope,
            ) = store.listAtStage(tenantId, stage, limit, offset, scope)
                scope: TenantScope,
            ) = store.listAtStage(tenantId, stage, scope)

            override fun append(
                prospectId: UUID,
                event: ProspectEvent,
                provenance: ProspectProvenance,
                scope: TenantScope,
            ) = store.append(prospectId, event, provenance, scope)
        }
    }

    @Bean
    fun jdbcIcTasks(dataSource: ObjectProvider<DataSource>): IcTasks {
        val store by lazy { JdbcTaskStore(dataSource.getObject()) }
        return object : IcTasks {
            override fun open(
                task: Task,
                provenance: TaskProvenance,
            ) = store.create(task, provenance)

            override fun state(taskId: UUID) = store.load(taskId)

            override fun append(
                taskId: UUID,
                event: TaskEvent,
                provenance: TaskProvenance,
            ) = store.append(taskId, event, provenance)

            override fun listForSubject(
                subjectType: String,
                subjectId: String,
            ) = store.listForSubject(subjectType, subjectId)

            override fun openUnlessOpen(
                task: Task,
                provenance: TaskProvenance,
            ) = store.openUnlessOpen(task, provenance)
        }
    }

    @Bean
    fun jdbcScreeningRules(dataSource: ObjectProvider<DataSource>): ScreeningRules {
        val store by lazy { JdbcScreeningRuleStore(dataSource.getObject()) }
        return object : ScreeningRules {
            override fun define(
                tenantId: UUID,
                ruleId: String,
                name: String,
                criteria: String,
                actor: String,
                provenance: ProspectProvenance,
                scope: TenantScope,
            ) = store.define(tenantId, ruleId, name, criteria, actor, provenance, scope)

            override fun retire(
                tenantId: UUID,
                ruleId: String,
                actor: String,
                provenance: ProspectProvenance,
                scope: TenantScope,
            ) = store.retire(tenantId, ruleId, actor, provenance, scope)

            override fun activeRules(
                tenantId: UUID,
                scope: TenantScope,
            ) = store.activeRules(tenantId, scope)
        }
    }
}
