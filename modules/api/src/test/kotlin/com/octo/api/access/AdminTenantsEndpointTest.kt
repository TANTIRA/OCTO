package com.octo.api.access

import com.octo.api.OctoApplication
import com.octo.api.access.persistence.AccessAdministration
import com.octo.api.access.persistence.AccessProvenance
import com.octo.api.access.persistence.TenantSettings
import com.octo.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/**
 * `/api/v1/admin/tenants` end to end with a fake administration store: a platform admin provisions,
 * a tenant ADMIN manages their own members, every other caller — member, non-member, no token —
 * is denied, and the state machine's rejected transitions surface as 409.
 */
class AdminTenantsEndpointTest {
    private val platformAdmin = UUID.randomUUID()
    private val tenantAdmin = UUID.randomUUID()
    private val analyst = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val access = FakeAccess()
    private val settings = FakeSettings()
    private val directory =
        TenantDirectory { id ->
            when (id) {
                tenantAdmin -> listOf(TenantAccess(tenantId, "acme", TenantRole.ADMIN))
                analyst -> listOf(TenantAccess(tenantId, "acme", TenantRole.ANALYST))
                else -> emptyList()
            }
        }

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(
                AccessAdministration::class.java,
                Supplier { access },
                { it.isPrimary = true },
            ).withBean(
                TenantDirectory::class.java,
                Supplier { directory },
                { it.isPrimary = true },
            ).withBean(
                TenantSettings::class.java,
                Supplier { settings },
                { it.isPrimary = true },
            ).withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
                "OCTO_PLATFORM_ADMINS=$platformAdmin",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun MockMvc.provision(
        caller: UUID,
        slug: String = "new-firm",
        firstAdmin: UUID = UUID.randomUUID(),
    ) = perform(
        post("/api/v1/admin/tenants")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"slug":"$slug","displayName":"New Firm","firstAdminUserId":"$firstAdmin"}""")
            .with(jwt().jwt { it.subject(caller.toString()) }),
    )

    private fun MockMvc.memberEvent(
        caller: UUID,
        tenant: UUID = tenantId,
        user: UUID = UUID.randomUUID(),
        body: String,
    ) = perform(
        post("/api/v1/admin/tenants/$tenant/members/$user")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .with(jwt().jwt { it.subject(caller.toString()) }),
    )

    @Test
    fun `a platform admin provisions a tenant and its first admin`() {
        run { mvc ->
            mvc
                .provision(platformAdmin)
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.slug").value("new-firm"))
                .andExpect(jsonPath("$.firstAdmin.role").value("admin"))
                .andExpect(jsonPath("$.firstAdmin.status").value("active"))
            assertThat(access.provisioned.single().slug).isEqualTo("new-firm")
        }
    }

    @Test
    fun `nobody else can provision — tenant admin, member, and anonymous all get denied`() {
        run { mvc ->
            mvc.provision(tenantAdmin, "t-a").andExpect(status().isForbidden)
            mvc.provision(analyst, "t-b").andExpect(status().isForbidden)
            mvc
                .perform(
                    post("/api/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"slug":"t-c","displayName":"C","firstAdminUserId":"${UUID.randomUUID()}"}"""),
                ).andExpect(status().isForbidden)
            assertThat(access.provisioned).isEmpty()
        }
    }

    @Test
    fun `nobody grants their own access — self-grant at provision or member event is 409, not 500`() {
        run { mvc ->
            mvc
                .provision(platformAdmin, firstAdmin = platformAdmin)
                .andExpect(status().isConflict)
                .andExpect(status().reason(containsString("segregation of duties")))
            assertThat(access.provisioned).isEmpty()
            mvc
                .memberEvent(platformAdmin, user = platformAdmin, body = """{"type":"granted","role":"admin"}""")
                .andExpect(status().isConflict)
                .andExpect(status().reason(containsString("segregation of duties")))
            assertThat(access.events).isEmpty()
        }
    }

    @Test
    fun `a tenant admin grants a member in their own tenant only`() {
        run { mvc ->
            mvc
                .memberEvent(tenantAdmin, body = """{"type":"granted","role":"analyst"}""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.role").value("analyst"))
            mvc
                .memberEvent(tenantAdmin, tenant = UUID.randomUUID(), body = """{"type":"granted","role":"analyst"}""")
                .andExpect(status().isForbidden)
            mvc
                .memberEvent(analyst, body = """{"type":"granted","role":"viewer"}""")
                .andExpect(status().isForbidden)
        }
    }

    @Test
    fun `a rejected transition surfaces as 409 and an unregistered member as 404`() {
        run { mvc ->
            access.nextError = IllegalArgumentException("already active")
            mvc
                .memberEvent(tenantAdmin, body = """{"type":"granted","role":"analyst"}""")
                .andExpect(status().isConflict)
            access.nextError = null
            access.registered = false
            mvc
                .memberEvent(tenantAdmin, body = """{"type":"role-changed","role":"approver"}""")
                .andExpect(status().isNotFound)
            mvc
                .memberEvent(tenantAdmin, body = """{"type":"bogus"}""")
                .andExpect(status().isBadRequest)
            mvc
                .memberEvent(tenantAdmin, body = """{"type":"revoked"}""")
                .andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `a tenant admin writes and reads settings but an analyst cannot`() {
        run { mvc ->
            mvc
                .perform(
                    put("/api/v1/admin/tenants/$tenantId/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"key":"features.screening_dd","value":"true"}""")
                        .with(jwt().jwt { it.subject(tenantAdmin.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$['features.screening_dd']").value("true"))
            mvc
                .perform(
                    get("/api/v1/admin/tenants/$tenantId/settings")
                        .with(jwt().jwt { it.subject(tenantAdmin.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$['features.screening_dd']").value("true"))
            mvc
                .perform(
                    put("/api/v1/admin/tenants/$tenantId/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"key":"features.screening_dd","value":"false"}""")
                        .with(jwt().jwt { it.subject(analyst.toString()) }),
                ).andExpect(status().isForbidden)
            mvc
                .perform(
                    put("/api/v1/admin/tenants/$tenantId/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"key":"  ","value":"10"}""")
                        .with(jwt().jwt { it.subject(tenantAdmin.toString()) }),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `tenant admins cannot create or overwrite their own rate limit`() {
        run { mvc ->
            fun attempt() =
                mvc
                    .perform(
                        put("/api/v1/admin/tenants/$tenantId/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"key":"rate_limit_per_minute","value":"100000"}""")
                            .with(jwt().jwt { it.subject(tenantAdmin.toString()) }),
                    ).andExpect(status().isForbidden)

            attempt()
            assertThat(settings.rows).doesNotContainKey(tenantId to "rate_limit_per_minute")
            settings.rows[tenantId to "rate_limit_per_minute"] = "60"
            attempt()
            assertThat(settings.rows[tenantId to "rate_limit_per_minute"]).isEqualTo("60")
        }
    }

    @Test
    fun `platform admins can set a tenant rate limit without tenant membership`() {
        run { mvc ->
            mvc
                .perform(
                    put("/api/v1/admin/tenants/$tenantId/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"key":"rate_limit_per_minute","value":"120"}""")
                        .with(jwt().jwt { it.subject(platformAdmin.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.rate_limit_per_minute").value("120"))
            assertThat(settings.rows[tenantId to "rate_limit_per_minute"]).isEqualTo("120")
        }
    }

    @Test
    fun `analysts outsiders and anonymous callers cannot write the rate limit`() {
        run { mvc ->
            settings.rows[tenantId to "rate_limit_per_minute"] = "60"
            for (caller in listOf(analyst, UUID.randomUUID())) {
                mvc
                    .perform(
                        put("/api/v1/admin/tenants/$tenantId/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"key":"rate_limit_per_minute","value":"100000"}""")
                            .with(jwt().jwt { it.subject(caller.toString()) }),
                    ).andExpect(status().isForbidden)
            }
            mvc
                .perform(
                    put("/api/v1/admin/tenants/$tenantId/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"key":"rate_limit_per_minute","value":"100000"}"""),
                ).andExpect(status().isForbidden)
            assertThat(settings.rows[tenantId to "rate_limit_per_minute"]).isEqualTo("60")
        }
    }

    @Test
    fun `tenant admins cannot change settings in another tenant`() {
        val foreignTenant = UUID.randomUUID()
        run { mvc ->
            mvc
                .perform(
                    put("/api/v1/admin/tenants/$foreignTenant/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"key":"features.screening_dd","value":"true"}""")
                        .with(jwt().jwt { it.subject(tenantAdmin.toString()) }),
                ).andExpect(status().isForbidden)
            assertThat(settings.rows).isEmpty()
        }
    }

    /** Minimal in-memory settings: key → JSON text per tenant. */
    private class FakeSettings : TenantSettings {
        val rows = mutableMapOf<Pair<UUID, String>, String>()

        override fun get(
            tenantId: UUID,
            key: String,
            scope: TenantScope,
        ): String? = rows[tenantId to key]

        override fun all(
            tenantId: UUID,
            scope: TenantScope,
        ): Map<String, String> = rows.filterKeys { it.first == tenantId }.mapKeys { it.key.second }

        override fun put(
            tenantId: UUID,
            key: String,
            value: String,
            actor: String,
            provenance: AccessProvenance,
            scope: TenantScope,
        ) {
            rows[tenantId to key] = value
        }
    }

    /** Minimal in-memory administration: records calls, replays a one-member state machine. */
    private class FakeAccess : AccessAdministration {
        val provisioned = mutableListOf<Tenant>()
        val events = mutableListOf<MembershipEvent>()
        var registered = true
        var nextError: IllegalArgumentException? = null

        override fun provisionTenant(
            tenant: Tenant,
            adminUserId: UUID,
            grantor: String,
            registeredAt: Instant,
            provenance: AccessProvenance,
        ): MembershipState =
            // Same validation as JdbcAccessStore.provisionTenant; a refused grant provisions nothing.
            registered(tenant.id, adminUserId, registeredAt)
                .next(MembershipEvent.Granted(grantor, registeredAt, TenantRole.ADMIN))
                .also { provisioned += tenant }

        /** Like the real store: a rejected grant registers nothing. */
        override fun grant(
            tenantId: UUID,
            userId: UUID,
            event: MembershipEvent.Granted,
            provenance: AccessProvenance,
        ): MembershipState {
            nextError?.let { throw it }
            registered = true
            return append(tenantId, userId, event, provenance)
        }

        override fun append(
            tenantId: UUID,
            userId: UUID,
            event: MembershipEvent,
            provenance: AccessProvenance,
        ): MembershipState {
            val before = load(tenantId, userId) ?: throw NoSuchElementException("no member")
            nextError?.let { throw it }
            return before.next(event).also { events += event }
        }
    }
}
