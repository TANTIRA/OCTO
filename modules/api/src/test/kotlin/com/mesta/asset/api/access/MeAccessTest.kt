package com.mesta.asset.api.access

import com.mesta.asset.api.MestaAssetApplication
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import java.util.function.Supplier

/**
 * `GET /api/v1/me/access` end to end: the bearer JWT's subject resolves the caller's tenants, a
 * missing token is refused like every other endpoint, and a non-user-id subject yields no tenants.
 */
class MeAccessTest {
    private val userId = UUID.randomUUID()
    private val tenant = TenantAccess(UUID.randomUUID(), "acme-capital", TenantRole.ANALYST)

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(MestaAssetApplication::class.java)
            // The stub stands in for AccessConfiguration's JdbcAccessStore-backed directory; primary wins the injection.
            .withBean(
                TenantDirectory::class.java,
                Supplier { TenantDirectory { id -> if (id == userId) listOf(tenant) else emptyList() } },
                { it.isPrimary = true },
            ).withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    @Test
    fun `the caller's tenants come back under their user id`() {
        contextRunner.run { context ->
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
                .perform(get("/api/v1/me/access").with(jwt().jwt { it.subject(userId.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.tenants[0].tenantId").value(tenant.tenantId.toString()))
                .andExpect(jsonPath("$.tenants[0].slug").value("acme-capital"))
                .andExpect(jsonPath("$.tenants[0].role").value("analyst"))
        }
    }

    @Test
    fun `no token is refused, like every other endpoint`() {
        contextRunner.run { context ->
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
                .perform(get("/api/v1/me/access"))
                .andExpect(status().isForbidden)
        }
    }

    @Test
    fun `a token without a user-id subject holds no tenants — default deny`() {
        contextRunner.run { context ->
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
                .perform(get("/api/v1/me/access").with(jwt().jwt { it.subject("service-account") }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.userId").value("service-account"))
                .andExpect(jsonPath("$.tenants").isEmpty())
        }
    }
}
