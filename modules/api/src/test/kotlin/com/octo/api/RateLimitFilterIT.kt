package com.octo.api

import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.access.persistence.TenantSettings
import com.octo.persistence.TenantScope
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.function.Supplier

/**
 * `RateLimitFilter` end to end against a real Redis: a tenant's quota is shared by every member,
 * a multi-tenant caller's `X-Tenant-Id` is honored only inside their membership, and the public
 * health surface stays free. Redis is wired the way deployments wire it — `REDIS_HOST`/`REDIS_PORT`.
 * The no-Redis and dead-Redis paths are covered without Docker in [RateLimitFallbackTest].
 * Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class RateLimitFilterIT {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val solo = UUID.randomUUID() // member of tenantA only
    private val colleague = UUID.randomUUID() // also member of tenantA — shares solo's counter
    private val multi = UUID.randomUUID() // member of tenantA and tenantB

    private val directory =
        TenantDirectory { id ->
            when (id) {
                solo, colleague -> listOf(TenantAccess(tenantA, "a", TenantRole.ANALYST))
                multi ->
                    listOf(
                        TenantAccess(tenantA, "a", TenantRole.ANALYST),
                        TenantAccess(tenantB, "b", TenantRole.ANALYST),
                    )
                else -> emptyList()
            }
        }

    private val settings =
        object : TenantSettings {
            override fun get(
                tenantId: UUID,
                key: String,
                scope: TenantScope,
            ): String? = if (tenantId == tenantA || tenantId == tenantB) "2" else null

            override fun all(
                tenantId: UUID,
                scope: TenantScope,
            ): Map<String, String> = emptyMap()

            override fun put(
                tenantId: UUID,
                key: String,
                value: String,
                actor: String,
                provenance: AccessProvenance,
                scope: TenantScope,
            ) = Unit
        }

    private fun runner(
        host: String,
        port: Int,
    ) = WebApplicationContextRunner()
        .withUserConfiguration(OctoApplication::class.java)
        .withBean(
            TenantDirectory::class.java,
            Supplier { directory },
            { it.isPrimary = true },
        ).withBean(
            TenantSettings::class.java,
            Supplier { settings },
            { it.isPrimary = true },
        ).withPropertyValues(
            "spring.autoconfigure.exclude=" +
                "${DataSourceAutoConfiguration::class.qualifiedName}," +
                "${FlywayAutoConfiguration::class.qualifiedName}",
            "octo.rate-limit.default-per-minute=2",
            "REDIS_HOST=$host",
            "REDIS_PORT=$port",
        )

    private fun run(
        host: String,
        port: Int,
        block: (
            org.springframework.test.web.servlet.MockMvc,
        ) -> Unit,
    ) {
        runner(host, port).run { context ->
            block(
                MockMvcBuilders
                    .webAppContextSetup(context)
                    .apply<DefaultMockMvcBuilder>(springSecurity())
                    .build(),
            )
        }
    }

    private fun asUser(id: UUID) = jwt().jwt { it.subject(id.toString()) }

    @Test
    fun `a tenant's quota is shared by its members and over-limit calls get 429`() {
        run(redis.host, redis.firstMappedPort) { mvc ->
            val url = "/api/v1/me/access"
            // Quota is 2/minute for tenantA — consumed by the tenant, not the user.
            mvc.perform(get(url).with(asUser(solo))).andExpect(status().isOk)
            mvc.perform(get(url).with(asUser(solo))).andExpect(status().isOk)
            mvc.perform(get(url).with(asUser(colleague))).andExpect(status().isTooManyRequests)
            mvc.perform(get(url).with(asUser(solo))).andExpect(status().isTooManyRequests)

            // The public health surface is not quota'd — probes must keep working under pressure.
            mvc.perform(get("/actuator/health")).andExpect(status().isOk)
        }
    }

    @Test
    fun `a multi-tenant caller picks a boundary with X-Tenant-Id and cannot name a foreign one`() {
        run(redis.host, redis.firstMappedPort) { mvc ->
            val url = "/api/v1/me/access"
            // Without a header the multi-tenant caller lands on the per-user quota (default 2).
            mvc.perform(get(url).with(asUser(multi))).andExpect(status().isOk)
            mvc.perform(get(url).with(asUser(multi))).andExpect(status().isOk)
            mvc.perform(get(url).with(asUser(multi))).andExpect(status().isTooManyRequests)

            // A foreign tenant id in the header is ignored — still the per-user counter, still over.
            mvc
                .perform(get(url).header(RateLimitFilter.TENANT_HEADER, UUID.randomUUID().toString()).with(asUser(multi)))
                .andExpect(status().isTooManyRequests)
        }
    }

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)
    }
}
