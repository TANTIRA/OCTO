package com.mesta.asset.api.access

import com.mesta.asset.api.access.persistence.AccessProvenance
import com.mesta.asset.api.access.persistence.JdbcAccessStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * `JdbcAccessStore` against the real V8 schema: tenant and member creation, grant/change/revoke
 * round-trips, `tenantsOf` derivation, and the trigger refusing what bypasses the state machine.
 * Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccessStoreIT {
    private val store: JdbcAccessStore by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("mesta")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        JdbcAccessStore(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    private val provenance = AccessProvenance("integration-test", UUID.randomUUID())
    private val grantor = UUID.randomUUID().toString()

    private fun newMember(): Pair<Tenant, UUID> {
        val tenant = Tenant(UUID.randomUUID(), "tenant-${UUID.randomUUID().toString().take(8)}", "Test Tenant")
        val userId = UUID.randomUUID()
        store.createTenant(tenant, provenance)
        store.registerMember(tenant.id, userId, at(0), provenance)
        return tenant to userId
    }

    @Test
    fun `grant, role change and revocation round-trip through tenantsOf`() {
        val (tenant, userId) = newMember()
        assertThat(store.tenantsOf(userId)).isEmpty()

        store.append(tenant.id, userId, MembershipEvent.Granted(grantor, at(1), TenantRole.ANALYST), provenance)
        assertThat(store.tenantsOf(userId)).containsExactly(TenantAccess(tenant.id, tenant.slug, TenantRole.ANALYST))

        store.append(tenant.id, userId, MembershipEvent.RoleChanged(grantor, at(2), TenantRole.APPROVER), provenance)
        assertThat(store.tenantsOf(userId).single().role).isEqualTo(TenantRole.APPROVER)

        store.append(tenant.id, userId, MembershipEvent.Revoked(grantor, at(3), "off-boarded"), provenance)
        assertThat(store.tenantsOf(userId)).isEmpty()
        assertThat(store.load(tenant.id, userId)?.status).isEqualTo(MembershipStatus.REVOKED)
    }

    @Test
    fun `access is scoped per tenant — a grant elsewhere does not leak`() {
        val (tenantA, userId) = newMember()
        val (tenantB, _) = newMember()
        store.registerMember(tenantB.id, userId, at(0), provenance)

        store.append(tenantA.id, userId, MembershipEvent.Granted(grantor, at(1), TenantRole.VIEWER), provenance)

        assertThat(store.tenantsOf(userId).map { it.tenantId }).containsExactly(tenantA.id)
        assertThat(store.load(tenantB.id, userId)?.status).isEqualTo(MembershipStatus.NONE)
    }

    @Test
    fun `a transition the state machine rejects is refused before anything is written`() {
        val (tenant, userId) = newMember()

        assertThatThrownBy {
            store.append(tenant.id, userId, MembershipEvent.RoleChanged(grantor, at(1), TenantRole.ADMIN), provenance)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no active membership")
        assertThatThrownBy {
            store.append(tenant.id, userId, MembershipEvent.Granted(userId.toString(), at(1), TenantRole.ADMIN), provenance)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("segregation of duties")
        assertThatThrownBy {
            store.append(tenant.id, UUID.randomUUID(), MembershipEvent.Granted(grantor, at(1), TenantRole.ANALYST), provenance)
        }.isInstanceOf(NoSuchElementException::class.java)
        assertThat(store.tenantsOf(userId)).isEmpty()
    }

    @Test
    fun `the trigger refuses what a direct caller writes — no state machine required`() {
        val (tenant, userId) = newMember()
        store.append(tenant.id, userId, MembershipEvent.Granted(grantor, at(1), TenantRole.ANALYST), provenance)

        // A second grant while active, written straight to SQL the way a bypassing caller would.
        assertThatThrownBy {
            execute(
                """
                insert into mesta.tenant_member_event (tenant_id, user_id, event_type, role, actor, occurred_at, correlation_id)
                values ('${tenant.id}', '$userId', 'granted', 'admin', '${UUID.randomUUID()}', now(), gen_random_uuid())
                """.trimIndent(),
            )
        }.isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo(CHECK_VIOLATION) }

        // Nobody grants their own access, whoever writes the row.
        val newcomer = UUID.randomUUID()
        store.registerMember(tenant.id, newcomer, at(0), provenance)
        assertThatThrownBy {
            execute(
                """
                insert into mesta.tenant_member_event (tenant_id, user_id, event_type, role, actor, occurred_at, correlation_id)
                values ('${tenant.id}', '$newcomer', 'granted', 'admin', '$newcomer', now(), gen_random_uuid())
                """.trimIndent(),
            )
        }.isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo(CHECK_VIOLATION) }
    }

    private fun execute(sql: String) {
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private companion object {
        /** SQLSTATE the V8 trigger's check_violation raises. */
        const val CHECK_VIOLATION = "23514"

        val T0: Instant = Instant.parse("2026-09-25T09:00:00Z")

        fun at(minutes: Long): Instant = T0.plusSeconds(minutes * 60)

        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mesta")
                .withUsername("mesta")
                .withPassword("mesta")
    }
}
