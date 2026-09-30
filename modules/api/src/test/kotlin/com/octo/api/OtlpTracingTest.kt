package com.octo.api

import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * docs/reliability.md §4 "every env var the compose file passes is read by something": the variable
 * deploy/dokploy.compose.yml derives from OTEL_EXPORTER_OTLP_ENDPOINT must switch the span exporter on,
 * and its absence (local runs, tests) must leave nothing trying to reach a collector (#306).
 */
class OtlpTracingTest {
    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName}," +
                    "${FlywayAutoConfiguration::class.qualifiedName}," +
                    "${RedisAutoConfiguration::class.qualifiedName}",
            )

    @Test
    fun `the compose-provided endpoint enables OTLP span export`() {
        contextRunner
            // The exact name compose sets, through the property source real env vars go through (relaxed binding).
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource(
                        "compose-env",
                        mapOf<String, Any>("MANAGEMENT_OTLP_TRACING_ENDPOINT" to "http://otel-collector:4318/v1/traces"),
                    ),
                )
            }.run { context ->
                // The OTLP/HTTP exporter is runtimeOnly, so assert its runtime class rather than import it.
                assertThat(context).hasSingleBean(SpanExporter::class.java)
                assertThat(context.getBean(SpanExporter::class.java).javaClass.simpleName).isEqualTo("OtlpHttpSpanExporter")
            }
    }

    @Test
    fun `no endpoint means no exporter`() {
        contextRunner.run { context -> assertThat(context).doesNotHaveBean(SpanExporter::class.java) }
    }
}
