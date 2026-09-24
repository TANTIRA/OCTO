package com.mesta.asset.api.access

import com.mesta.asset.api.access.persistence.JdbcAccessStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
}
