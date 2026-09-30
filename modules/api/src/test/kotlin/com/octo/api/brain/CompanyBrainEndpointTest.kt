package com.octo.api.brain

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.agents.AgentsCallException
import com.octo.api.agents.AgentsClient
import com.octo.api.agents.AgentsUnavailableException
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.IOException
import java.util.UUID
import java.util.function.Supplier

/**
 * `/api/v1/company-brain/query` end to end with a stubbed sidecar client: any role in the
 * tenant may ask (the workflow mints nothing), another tenant's id is 404, and the sidecar
 * error contract maps 503/502 like the prospect triggers.
 */
class CompanyBrainEndpointTest {
    private val member = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val otherTenant = UUID.randomUUID()

    private var agentsBehavior: (Map<String, Any>) -> Map<String, Any> = {
        mapOf(
            "status" to "completed",
            "answer" to "two prospects stand at ic-review",
            "verdict" to mapOf("supported_probability" to 0.9),
        )
    }
    private val agents = AgentsClient { _, payload -> agentsBehavior(payload) }

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
                AgentsClient::class.java,
                Supplier { agents },
                { it.isPrimary = true },
            ).withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun MockMvc.ask(
        caller: UUID,
        tenant: UUID = tenantId,
    ) = perform(
        post("/api/v1/company-brain/query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"tenantId":"$tenant","question":"who is at ic-review?"}""")
            .with(jwt().jwt { it.subject(caller.toString()) }),
    )

    @Test
    fun `any role asks and the judged answer passes through`() {
        run { mvc ->
            mvc
                .ask(member)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.answer").value("two prospects stand at ic-review"))
            mvc.ask(viewer).andExpect(status().isOk)
        }
    }

    @Test
    fun `another tenant's id and an unauthenticated call are denied`() {
        run { mvc ->
            mvc.ask(member, tenant = otherTenant).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/company-brain/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","question":"q"}"""),
                ).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `sidecar down answers 503, a flag-off sidecar 503 too, its own failure 502`() {
        run { mvc ->
            agentsBehavior = { throw AgentsUnavailableException(IOException("down")) }
            mvc.ask(member).andExpect(status().isServiceUnavailable)

            agentsBehavior = { throw AgentsCallException(503, "flag off") }
            mvc.ask(member).andExpect(status().isServiceUnavailable)

            agentsBehavior = { throw AgentsCallException(500, "model registry miss") }
            mvc.ask(member).andExpect(status().isBadGateway)
        }
    }

    @Test
    fun `a blank question is a 400`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/company-brain/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","question":"  "}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
        }
    }
}
