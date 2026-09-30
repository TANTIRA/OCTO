package com.octo.api.agents

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.access.persistence.TenantSettingKeys
import com.octo.api.access.persistence.TenantSettings
import com.octo.persistence.TenantScope
import org.junit.jupiter.api.Test
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import java.util.function.Supplier

/**
 * `/api/v1/agent-context` end to end: tenant members read the warm-context brief the
 * sidecar prepends to drafter prompts (F11), non-members see 404, an unset key is a
 * null value rather than an error. The JSON-text setting unwraps to the string.
 */
class AgentContextEndpointTest {
    private val member = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier {
                    TenantDirectory { id ->
                        when (id) {
                            member -> listOf(TenantAccess(tenantId, "acme", TenantRole.ANALYST))
                            viewer -> listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
                            else -> emptyList()
                        }
                    }
                },
                { it.isPrimary = true },
            ).withBean(
                TenantSettings::class.java,
                Supplier { FakeSettings() },
                { it.isPrimary = true },
            ).withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun MockMvc.read(
        caller: UUID,
        tenant: UUID = tenantId,
    ) = perform(
        get("/api/v1/agent-context?tenantId=$tenant")
            .with(jwt().jwt { it.subject(caller.toString()) }),
    )

    @Test
    fun `members and viewers read the unwrapped warm context`() {
        run { mvc ->
            mvc
                .read(member)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.warmContext").value("healthcare specialist thesis"))
            mvc
                .read(viewer)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.warmContext").value("healthcare specialist thesis"))
        }
    }

    @Test
    fun `non-members and unset keys are not found or null`() {
        run { mvc ->
            mvc.read(member, tenant = UUID.randomUUID()).andExpect(status().isNotFound)
            mvc
                .perform(get("/api/v1/agent-context?tenantId=$tenantId"))
                .andExpect(status().isForbidden)
        }
    }

    private class FakeSettings : TenantSettings {
        override fun get(
            tenantId: UUID,
            key: String,
            scope: TenantScope,
        ): String? = if (key == TenantSettingKeys.AGENT_WARM_CONTEXT) "\"healthcare specialist thesis\"" else null

        override fun all(
            tenantId: UUID,
            scope: TenantScope,
        ): Map<String, String> = mapOf(TenantSettingKeys.AGENT_WARM_CONTEXT to "\"healthcare specialist thesis\"")

        override fun put(
            tenantId: UUID,
            key: String,
            value: String,
            actor: String,
            provenance: AccessProvenance,
            scope: TenantScope,
        ) = Unit
    }
}
