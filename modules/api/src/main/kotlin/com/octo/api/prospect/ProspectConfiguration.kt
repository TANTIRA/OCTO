package com.octo.api.prospect

import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.TenantScope
import com.octo.dealsourcing.persistence.JdbcProspectStore
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.dealsourcing.persistence.ProspectStore
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

            override fun load(
                id: UUID,
                scope: TenantScope,
            ) = store.load(id, scope)

            override fun listAtStage(
                tenantId: UUID,
                stage: ProspectStage,
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
}
