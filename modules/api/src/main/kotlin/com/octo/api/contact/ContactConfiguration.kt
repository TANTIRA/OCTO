package com.octo.api.contact

import com.octo.api.contact.persistence.JdbcContactLeadStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/** Wired lazily like `AccessConfiguration` so a context without a datasource still starts. */
@Configuration(proxyBeanMethods = false)
class ContactConfiguration {
    @Bean
    fun jdbcContactLeadStore(dataSource: ObjectProvider<DataSource>): ContactStore {
        val store by lazy { JdbcContactLeadStore(dataSource.getObject()) }
        return ContactStore { lead -> store.record(lead) }
    }
}
