package com.mesta.asset.api.asset

import com.mesta.asset.api.asset.persistence.JdbcAssetStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/** Wires the asset store like `AccessConfiguration`: resolved lazily so a context without a datasource still starts. */
@Configuration(proxyBeanMethods = false)
class AssetConfiguration {
    @Bean
    fun jdbcAssetStore(dataSource: ObjectProvider<DataSource>): AssetStore {
        val store by lazy { JdbcAssetStore(dataSource.getObject()) }
        return AssetStore { id -> store.load(id) }
    }
}
