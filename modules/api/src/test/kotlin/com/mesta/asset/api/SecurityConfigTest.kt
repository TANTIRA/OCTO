package com.mesta.asset.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SecurityConfigTest {
    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(MestaAssetApplication::class.java)
            .withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
                // Mirrors application.yml — the context runner does not load it.
                "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
                "management.endpoint.health.probes.enabled=true",
                // No datasource in this runner, so the readiness group holds only the readiness state.
                "management.endpoint.health.group.readiness.include=readinessState",
            )

    @Test
    fun `health and probes are public and everything else requires authentication`() {
        contextRunner.run { context ->
            val mvc: MockMvc =
                MockMvcBuilders
                    .webAppContextSetup(context)
                    .apply<DefaultMockMvcBuilder>(springSecurity())
                    .build()

            // SpringApplication publishes these on start-up; the context runner does not, so the probes would
            // report DOWN here and hide whether the auth boundary let the request through.
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT)
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC)
            for (public in listOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info")) {
                mvc.perform(get(public)).andExpect(status().isOk)
            }
            for (protected in listOf("/actuator/prometheus", "/actuator/metrics")) {
                mvc.perform(get(protected)).andExpect(status().isForbidden)
            }
            mvc.perform(get("/api/funds")).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `a filter chain bean exists and context still starts without a JWKS URL`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(SecurityFilterChain::class.java)
        }
    }

    @Test
    fun `context starts when a JWKS URL is configured`() {
        contextRunner
            .withPropertyValues(
                "AUTH_JWKS_URL=https://issuer.example.invalid/.well-known/jwks.json",
                "AUTH_ISSUER=https://issuer.example.invalid/",
            ).run { context ->
                assertThat(context).hasSingleBean(SecurityFilterChain::class.java)
            }
    }
}
