package com.mesta.asset.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The booted application against a real database, as the compose healthcheck sees it: readiness includes the
 * database (application.yml), so losing it takes the instance out of rotation while liveness stays up and the
 * orchestrator does not restart it (docs/reliability.md §4). Boots with the same DB_* variables compose passes.
 * Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadinessIT {
    @Autowired
    private lateinit var http: TestRestTemplate

    private fun status(probe: String): HttpStatus =
        HttpStatus.valueOf(http.getForEntity("/actuator/health/$probe", String::class.java).statusCode.value())

    @Test
    fun `readiness follows the database and liveness does not`() {
        assertThat(status("liveness")).isEqualTo(HttpStatus.OK)
        assertThat(status("readiness")).describedAs("ready with the database up").isEqualTo(HttpStatus.OK)

        postgres.stop()

        assertThat(status("readiness")).describedAs("not ready once the database is gone").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(status("liveness")).describedAs("still alive: a restart would not bring the database back").isEqualTo(HttpStatus.OK)
    }

    companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("DB_HOST", postgres::getHost)
            registry.add("DB_PORT") { postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) }
            registry.add("DB_NAME", postgres::getDatabaseName)
            registry.add("DB_USER", postgres::getUsername)
            registry.add("DB_PASSWORD", postgres::getPassword)
            // Fail fast on the dead database instead of waiting Hikari's 30-second default per probe.
            registry.add("spring.datasource.hikari.connection-timeout") { 2000 }
        }
    }
}
