package com.octo.api

import jakarta.servlet.DispatcherType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class SecurityConfigTest {
    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withPropertyValues(
                // Redis auto-config would otherwise mint a localhost factory and the redis health
                // contributor pulls the aggregate DOWN when no dev redis happens to be running.
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName}," +
                    "${FlywayAutoConfiguration::class.qualifiedName}," +
                    "${RedisAutoConfiguration::class.qualifiedName}",
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
                mvc
                    .perform(get(public))
                    .andExpect { result ->
                        assertThat(result.response.status)
                            .`as`("GET $public → ${result.response.contentAsString.take(300)}")
                            .isEqualTo(200)
                    }
            }
            for (protected in listOf("/actuator/prometheus", "/actuator/metrics")) {
                mvc.perform(get(protected)).andExpect(status().isForbidden)
            }
            mvc.perform(get("/api/funds")).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `the error dispatch is permitted so a failure is not masked as 403`() {
        // A controller exception or a filter's sendError() re-dispatches to /error with the
        // SecurityContext cleared; denying that dispatch answered 403 for every failure,
        // real status included. The dispatch must reach the error handler instead.
        contextRunner.run { context ->
            val mvc: MockMvc =
                MockMvcBuilders
                    .webAppContextSetup(context)
                    .apply<DefaultMockMvcBuilder>(springSecurity())
                    .build()
            mvc
                .perform(
                    get("/error").with { request ->
                        request.dispatcherType = DispatcherType.ERROR
                        request
                    },
                ).andExpect { result -> assertThat(result.response.status).isNotEqualTo(403) }
        }
    }

    @Test
    fun `filter chain beans exist and context still starts without a JWKS URL`() {
        contextRunner.run { context ->
            // JWT resource-server chain + the dedicated Helius webhook chain.
            assertThat(context).getBeans(SecurityFilterChain::class.java).hasSize(2)
        }
    }

    @Test
    fun `context starts when a JWKS URL is configured`() {
        contextRunner
            .withPropertyValues(
                "AUTH_JWKS_URL=https://issuer.example.invalid/.well-known/jwks.json",
                "AUTH_ISSUER=https://issuer.example.invalid/",
            ).run { context ->
                assertThat(context).getBeans(SecurityFilterChain::class.java).hasSize(2)
            }
    }

    private fun token(aud: Any) =
        Jwt
            .withTokenValue("t")
            .header("alg", "ES256")
            .claim(JwtClaimNames.AUD, aud)
            .expiresAt(Instant.now().plusSeconds(300))
            .build()

    @Test
    fun `audience validator accepts the configured string aud`() {
        val env = MockEnvironment().withProperty("AUTH_AUDIENCE", "authenticated")
        assertThat(bearerTokenValidator(env).validate(token("authenticated")).hasErrors()).isFalse()
    }

    @Test
    fun `audience validator accepts a list aud containing the configured value`() {
        val env = MockEnvironment().withProperty("AUTH_AUDIENCE", "authenticated")
        assertThat(bearerTokenValidator(env).validate(token(listOf("other", "authenticated"))).hasErrors()).isFalse()
    }

    @Test
    fun `audience validator rejects a wrong or missing aud`() {
        val env = MockEnvironment().withProperty("AUTH_AUDIENCE", "authenticated")
        assertThat(bearerTokenValidator(env).validate(token("service_role")).hasErrors()).isTrue()
        assertThat(bearerTokenValidator(env).validate(token(listOf("other"))).hasErrors()).isTrue()
    }

    @Test
    fun `audience is unchecked when AUTH_AUDIENCE is unset`() {
        val env = MockEnvironment()
        assertThat(bearerTokenValidator(env).validate(token("anything")).hasErrors()).isFalse()
    }
}
