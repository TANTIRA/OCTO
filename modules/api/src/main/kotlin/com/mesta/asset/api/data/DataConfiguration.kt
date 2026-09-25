package com.mesta.asset.api.data

import com.mesta.asset.ingestion.persistence.JdbcTimeSeriesStore
import com.mesta.asset.ingestion.persistence.TimeSeriesQuery
import com.mesta.asset.ingestion.persistence.TimeSeriesReader
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import javax.sql.DataSource

/** Wires the time-series reader like `AccessConfiguration`: resolved lazily so a context without a datasource still starts. */
@Configuration(proxyBeanMethods = false)
class DataConfiguration {
    @Bean
    fun jdbcTimeSeriesReader(dataSource: ObjectProvider<DataSource>): TimeSeriesReader {
        val store by lazy { JdbcTimeSeriesStore(dataSource.getObject()) }
        return object : TimeSeriesReader {
            override fun datasetTenant(datasetId: UUID) = store.datasetTenant(datasetId)

            override fun query(query: TimeSeriesQuery) = store.query(query)
        }
    }
}
