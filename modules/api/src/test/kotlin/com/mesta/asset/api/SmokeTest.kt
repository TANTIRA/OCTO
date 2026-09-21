package com.mesta.asset.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

class SmokeTest {
    private val contextRunner = WebApplicationContextRunner()
        .withUserConfiguration(MestaAssetApplication::class.java)
        .withPropertyValues(
            "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
        )

    @Test
    fun `application context starts`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(MestaAssetApplication::class.java)
        }
    }
}
