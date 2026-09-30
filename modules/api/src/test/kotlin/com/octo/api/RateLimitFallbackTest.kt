package com.octo.api

import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.contact.ContactStore
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import java.util.function.Supplier

/**
 * #321 without Docker: the limiters exist and enforce with no Redis configured and with a dead
 * Redis (in-process windows, never fail-open), and traffic that has no tenant — failed
 * authentication on either chain, the anonymous lead form — is charged per client IP.
 */
class RateLimitFallbackTest {
    private val tenant = UUID.randomUUID()
    private val solo = UUID.randomUUID()
    private val colleague = UUID.randomUUID()

    private val directory =
        TenantDirectory { id ->
            if (id == solo || id == colleague) listOf(TenantAccess(tenant, "t", TenantRole.ANALYST)) else emptyList()
        }

    private val runner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier { directory },
                { it.isPrimary = true },
            ).withBean(ContactStore::class.java, Supplier { ContactStore { } }, { it.isPrimary = true })
            .withPropertyValues(
                "spring.autoconfigure.exclude=" +
                    "${DataSourceAutoConfiguration::class.qualifiedName}," +
                    "${FlywayAutoConfiguration::class.qualifiedName}," +
                    // Mirrors application.yml — the context runner does not load it.
                    "${RedisAutoConfiguration::class.qualifiedName}," +
                    "${RedisRepositoriesAutoConfiguration::class.qualifiedName}",
                "octo.reports.poll=false",
                "octo.rate-limit.default-per-minute=2",
                "HELIUS_WEBHOOK_SECRET=webhook-secret",
            )

    private fun run(
        vararg properties: String,
        block: (MockMvc) -> Unit,
    ) {
        runner.withPropertyValues(*properties).run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun asUser(id: UUID) = jwt().jwt { it.subject(id.toString()) }

    @Test
    fun `without REDIS_HOST the tenant quota is still enforced in-process`() {
        run { mvc ->
            // TenantSettings has no override for this tenant, so the default of 2 applies.
            mvc.perform(get("/api/v1/me/access").with(asUser(solo))).andExpect(status().isOk)
            mvc.perform(get("/api/v1/me/access").with(asUser(solo))).andExpect(status().isOk)
            mvc.perform(get("/api/v1/me/access").with(asUser(colleague))).andExpect(status().isTooManyRequests)
        }
    }

    @Test
    fun `a dead Redis falls back to in-process quotas instead of admitting`() {
        // Nothing listens on port 1: every Redis call fails and the counter must still enforce.
        run("REDIS_HOST=127.0.0.1", "REDIS_PORT=1") { mvc ->
            mvc.perform(get("/api/v1/me/access").with(asUser(solo))).andExpect(status().isOk)
            mvc.perform(get("/api/v1/me/access").with(asUser(solo))).andExpect(status().isOk)
            mvc.perform(get("/api/v1/me/access").with(asUser(solo))).andExpect(status().isTooManyRequests)
        }
    }

    @Test
    fun `failed authentication floods are cut off per IP and rotating X-Forwarded-For does not escape`() {
        run { mvc ->
            repeat(ClientIpRateLimitFilter.AUTH_FAILURES_PER_MINUTE) {
                // Only the rightmost entry is the proxy-appended peer; the rest is attacker-chosen.
                mvc
                    .perform(get("/api/v1/me/access").header("X-Forwarded-For", "10.9.$it.1, 203.0.113.7"))
                    .andExpect(status().isForbidden)
            }
            mvc
                .perform(get("/api/v1/me/access").header("X-Forwarded-For", "198.51.100.99, 203.0.113.7"))
                .andExpect(status().isTooManyRequests)
            // Another client is untouched, and authenticated traffic never counted toward the limit.
            mvc
                .perform(get("/api/v1/me/access").header("X-Forwarded-For", "203.0.113.8"))
                .andExpect(status().isForbidden)
            mvc.perform(get("/api/v1/me/access").with(asUser(solo))).andExpect(status().isOk)
        }
    }

    @Test
    fun `wrong webhook secrets are cut off per IP before the digest compare`() {
        run { mvc ->
            val webhook = "/api/v1/ingestion/webhooks/helius"
            repeat(ClientIpRateLimitFilter.AUTH_FAILURES_PER_MINUTE) {
                mvc.perform(post(webhook).header("Authorization", "guess-$it")).andExpect(status().isUnauthorized)
            }
            mvc.perform(post(webhook).header("Authorization", "guess-final")).andExpect(status().isTooManyRequests)
        }
    }

    @Test
    fun `the anonymous lead form is limited per IP`() {
        run { mvc ->
            val contact = { post("/api/v1/contact").contentType(MediaType.APPLICATION_JSON).content("{}") }
            repeat(ClientIpRateLimitFilter.CONTACT_PER_MINUTE) {
                mvc.perform(contact()).andExpect(status().isBadRequest)
            }
            mvc.perform(contact()).andExpect(status().isTooManyRequests)
        }
    }
}
