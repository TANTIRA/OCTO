package com.mesta.asset.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
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
                "management.endpoints.web.exposure.include=health,info",
            )

    @Test
    fun `health endpoint is public and everything else requires authentication`() {
        contextRunner.run { context ->
            val mvc: MockMvc =
                MockMvcBuilders
                    .webAppContextSetup(context)
                    .apply<DefaultMockMvcBuilder>(springSecurity())
                    .build()

            mvc.perform(get("/actuator/health")).andExpect(status().isOk)
            mvc.perform(get("/actuator/info")).andExpect(status().isOk)
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
