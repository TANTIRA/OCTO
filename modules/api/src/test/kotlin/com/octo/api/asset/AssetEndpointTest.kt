package com.mesta.asset.api.asset

import com.mesta.asset.api.MestaAssetApplication
import com.mesta.asset.api.access.TenantAccess
import com.mesta.asset.api.access.TenantDirectory
import com.mesta.asset.api.access.TenantRole
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/** `GET /api/v1/assets/{id}` end to end with stubbed store and directory: a member reads it, everyone else sees 404. */
class AssetEndpointTest {
    private val member = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val asset =
        AssetRecord(
            Asset(UUID.randomUUID(), tenantId, AssetType.FUND, "private-equity", "Fund I", region = "ID", tags = listOf("growth")),
            listOf(Identifier("lei", "5493001KJTIIGC8Y1R12")),
            supersededBy = null,
            recordedAt = Instant.parse("2026-09-25T00:00:00Z"),
        )

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(MestaAssetApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier {
                    TenantDirectory { id ->
                        if (id ==
                            member
                        ) {
                            listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
                        } else {
                            emptyList()
                        }
                    }
                },
                { it.isPrimary = true },
            ).withBean(
                AssetStore::class.java,
                Supplier {
                    AssetStore { id ->
                        asset.takeIf { it.asset.id == id }
                    }
                },
                { it.isPrimary = true },
            ).withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    @Test
    fun `a member of the asset's tenant reads it in the Marquee shape`() {
        run { mvc ->
            mvc
                .perform(get("/api/v1/assets/${asset.asset.id}").with(jwt().jwt { it.subject(member.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.type").value("fund"))
                .andExpect(jsonPath("$.assetClass").value("private-equity"))
                .andExpect(jsonPath("$.identifiers[0].scheme").value("lei"))
                .andExpect(jsonPath("$.region").value("ID"))
                .andExpect(jsonPath("$.tags[0]").value("growth"))
                .andExpect(jsonPath("$.supersededBy").doesNotExist())
        }
    }

    @Test
    fun `a non-member, a service account and an unknown id all get 404`() {
        run { mvc ->
            mvc
                .perform(
                    get("/api/v1/assets/${asset.asset.id}").with(
                        jwt().jwt {
                            it.subject(UUID.randomUUID().toString())
                        },
                    ),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    get("/api/v1/assets/${asset.asset.id}").with(jwt().jwt { it.subject("service-account") }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    get("/api/v1/assets/${UUID.randomUUID()}").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `no token is refused like every other endpoint`() {
        run { mvc ->
            mvc.perform(get("/api/v1/assets/${asset.asset.id}")).andExpect(status().isForbidden)
        }
    }
}
