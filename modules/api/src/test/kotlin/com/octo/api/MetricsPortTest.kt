package com.octo.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Real sockets, not MockMvc: the scrape path is decided by the connector a request arrives on (#338),
 * so the proof has to go through both Tomcat connectors.
 */
class MetricsPortTest {
    private val client = HttpClient.newHttpClient()

    private fun get(
        port: Int,
        path: String,
    ): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `prometheus is anonymous on the metrics port only, and the public port is unchanged`() {
        val metricsPort = ServerSocket(0).use { it.localPort }
        SpringApplicationBuilder(OctoApplication::class.java)
            .properties(
                // Same no-datasource setup as SecurityConfigTest; application.yml is skipped so its
                // datasource url does not wire the routing DataSource.
                "spring.config.location=optional:classpath:/none/",
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName}," +
                    "${FlywayAutoConfiguration::class.qualifiedName}," +
                    "${RedisAutoConfiguration::class.qualifiedName}",
                "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.group.readiness.include=readinessState",
                "server.port=0",
                "octo.metrics.port=$metricsPort",
            ).run()
            .use { context ->
                val apiPort = (context as WebServerApplicationContext).webServer.port

                val scrape = get(metricsPort, "/actuator/prometheus")
                assertThat(scrape.statusCode()).isEqualTo(200)
                assertThat(scrape.body()).contains("jvm_memory_used_bytes")

                // The public port still demands a bearer token for metrics; health stays public there.
                assertThat(get(apiPort, "/actuator/prometheus").statusCode()).isIn(401, 403)
                assertThat(get(apiPort, "/actuator/metrics").statusCode()).isIn(401, 403)
                assertThat(get(apiPort, "/actuator/health/liveness").statusCode()).isEqualTo(200)

                // The scrape port serves the scrape and nothing else.
                for (path in listOf("/actuator/metrics", "/actuator/health", "/api/v1/funds")) {
                    assertThat(get(metricsPort, path).statusCode()).`as`("GET :metrics$path").isEqualTo(403)
                }
            }
    }

    @Test
    fun `a metrics port equal to the server port refuses to boot`() {
        val port = ServerSocket(0).use { it.localPort }
        val failure =
            runCatching {
                SpringApplicationBuilder(OctoApplication::class.java)
                    .properties(
                        "spring.config.location=optional:classpath:/none/",
                        "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName}," +
                            "${FlywayAutoConfiguration::class.qualifiedName}," +
                            "${RedisAutoConfiguration::class.qualifiedName}",
                        "server.port=$port",
                        "octo.metrics.port=$port",
                    ).run()
                    .close()
            }.exceptionOrNull()
        assertThat(failure).hasRootCauseMessage(
            "octo.metrics.port must be a fixed port (1-65535) different from server.port ($port), was $port",
        )
    }
}
