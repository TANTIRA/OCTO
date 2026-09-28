package com.octo.api.access

import com.octo.api.access.persistence.AccessAdministration
import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.access.persistence.JdbcAccessStore
import com.octo.api.access.persistence.JdbcTenantSettingsStore
import com.octo.api.access.persistence.TenantSettings
import com.octo.persistence.TenantScope
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Wires the access layer into the api. The store needs the datasource, but the context must still
 * start where no datasource exists (local slices that exclude persistence, readiness during a lost
 * database): the directory resolves lazily and fails on use — deny by default — rather than at boot.
 */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration {
    @Bean
    fun jdbcTenantDirectory(dataSource: ObjectProvider<DataSource>): TenantDirectory {
        val store by lazy { JdbcAccessStore(dataSource.getObject()) }
        return TenantDirectory { userId -> store.tenantsOf(userId) }
    }

    /** The write side of the access store, behind the same lazy boundary as the directory. */
    @Bean
    fun accessStoreAdministration(dataSource: ObjectProvider<DataSource>): AccessAdministration {
        val store by lazy { JdbcAccessStore(dataSource.getObject()) }
        return object : AccessAdministration {
            override fun provisionTenant(
                tenant: Tenant,
                adminUserId: UUID,
                grantor: String,
                registeredAt: Instant,
                provenance: AccessProvenance,
            ) = store.provisionTenant(tenant, adminUserId, grantor, registeredAt, provenance)

            override fun registerMember(
                tenantId: UUID,
                userId: UUID,
                registeredAt: Instant,
                provenance: AccessProvenance,
            ) = store.registerMember(tenantId, userId, registeredAt, provenance)

            override fun load(
                tenantId: UUID,
                userId: UUID,
            ) = store.load(tenantId, userId)

            override fun append(
                tenantId: UUID,
                userId: UUID,
                event: MembershipEvent,
                provenance: AccessProvenance,
            ) = store.append(tenantId, userId, event, provenance)
        }
    }

    /**
     * `OCTO_PLATFORM_ADMINS` — comma-separated JWT subjects allowed to provision tenants and manage
     * any tenant's members. Unset admits nobody (fail-closed); the variable is deliberately absent
     * from dev/test property defaults so a forgotten environment cannot self-elect admins.
     */
    @Bean
    fun platformAdmin(env: Environment): PlatformAdmin = PlatformAdmin(env.getProperty("OCTO_PLATFORM_ADMINS"))

    /** Per-tenant configuration (V31), behind the same lazy datasource boundary. */
    @Bean
    fun jdbcTenantSettings(dataSource: ObjectProvider<DataSource>): TenantSettings {
        val store by lazy { JdbcTenantSettingsStore(dataSource.getObject()) }
        return object : TenantSettings {
            override fun get(
                tenantId: UUID,
                key: String,
                scope: TenantScope,
            ) = store.get(tenantId, key, scope)

            override fun all(
                tenantId: UUID,
                scope: TenantScope,
            ) = store.all(tenantId, scope)

            override fun put(
                tenantId: UUID,
                key: String,
                value: String,
                actor: String,
                provenance: AccessProvenance,
                scope: TenantScope,
            ) = store.put(tenantId, key, value, actor, provenance, scope)
        }
    }
}
