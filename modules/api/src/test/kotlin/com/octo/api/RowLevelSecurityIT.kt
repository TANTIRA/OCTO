package com.octo.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.UUID

/**
 * V27's row-level security against the real schema, exercised as a non-superuser probe role —
 * the container's postgres login owns the objects and bypasses RLS, the way infra's
 * DB_MIGRATION_USER does, so a second login stands in for the deployed runtime role
 * (`octo_app`, infra/init-db-roles.sql). Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class RowLevelSecurityIT {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val member = UUID.randomUUID()
    private val outsider = UUID.randomUUID()

    private val owner by lazy {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .schemas("octo")
            .placeholders(mapOf("runtime_role" to postgres.username))
            .load()
            .migrate()
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    private val probe by lazy {
        DriverManagerDataSource(
            postgres.jdbcUrl,
            "rls_probe",
            "rls_probe",
        )
    }

    @BeforeEach
    fun seed() {
        owner.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                // Probe role: same privilege level the deployed runtime role holds
                // (schema usage + select/insert on the tables under test).
                s.execute(
                    "do \$\$ begin " +
                        "if exists (select 1 from pg_roles where rolname = 'rls_probe') then " +
                        "drop owned by rls_probe cascade; drop role rls_probe; " +
                        "end if; end \$\$",
                )
                s.execute("create role rls_probe login password 'rls_probe'")
                s.execute("grant usage on schema octo to rls_probe")
                s.execute("grant select, insert on octo.prospect, octo.prospect_event to rls_probe")

                // Fresh random tenants per test — the schema's append-only triggers rightly
                // refuse deletes, so tests isolate by tenant id instead of cleaning rows.
                listOf(tenantA, tenantB).forEach { t ->
                    s.execute(
                        "insert into octo.tenant (id, slug, display_name, source_system, correlation_id) " +
                            "values ('$t', 't-${t.toString().take(8)}', 'T', 'test', gen_random_uuid())",
                    )
                    s.execute(
                        "insert into octo.prospect (tenant_id, name, source, registered_at, source_system, actor, correlation_id) " +
                            "values ('$t', 'P', 'manual', now(), 'test', 'seeder', gen_random_uuid())",
                    )
                }
                // One event per prospect so indirect (subselect) policies are exercised.
                s.execute(
                    "insert into octo.prospect_event " +
                        "(prospect_id, event_type, stage_from, stage_to, actor, occurred_at, correlation_id) " +
                        "select id, 'advanced', 'sourced', 'screening', 'seeder', now(), gen_random_uuid() " +
                        "from octo.prospect",
                )
                // member is active in tenant A; outsider is granted then revoked there.
                s.execute(
                    "insert into octo.tenant_member (tenant_id, user_id, created_at, source_system, correlation_id) values " +
                        "('$tenantA', '$member', now(), 'test', gen_random_uuid()), " +
                        "('$tenantA', '$outsider', now(), 'test', gen_random_uuid())",
                )
                val grantor = UUID.randomUUID()
                s.execute(
                    "insert into octo.tenant_member_event " +
                        "(tenant_id, user_id, event_type, role, actor, rationale, occurred_at, correlation_id) values " +
                        "('$tenantA', '$member', 'granted', 'analyst', '$grantor', null, now(), gen_random_uuid()), " +
                        "('$tenantA', '$outsider', 'granted', 'viewer', '$grantor', null, now(), gen_random_uuid()), " +
                        "('$tenantA', '$outsider', 'revoked', null, '$grantor', 'because test', " +
                        "now() + interval '1 second', gen_random_uuid())",
                )
            }
            c.commit()
        }
    }

    private fun prospectCount(
        user: String?,
        tenants: String?,
    ): Int =
        probe.connection.use { c ->
            c.autoCommit = false
            c
                .prepareStatement(
                    "select set_config('app.user_id', ?, true), set_config('app.tenant_ids', ?, true)",
                ).use { s ->
                    s.setString(1, user)
                    s.setString(2, tenants)
                    s.execute()
                }
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from octo.prospect where tenant_id in ('$tenantA', '$tenantB')",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            }
        }

    @Test
    fun `an unscoped connection sees no tenant rows`() {
        assertThat(prospectCount(user = null, tenants = null)).isEqualTo(0)
        assertThat(prospectCount(user = "", tenants = "")).isEqualTo(0)
    }

    @Test
    fun `All scope sees every tenant's rows`() {
        assertThat(prospectCount(user = null, tenants = "*")).isEqualTo(2)
    }

    @Test
    fun `Tenants scope sees only listed tenants`() {
        assertThat(prospectCount(user = null, tenants = "$tenantA")).isEqualTo(1)
        assertThat(prospectCount(user = null, tenants = "$tenantA,$tenantB")).isEqualTo(2)
        assertThat(prospectCount(user = null, tenants = "${UUID.randomUUID()}")).isEqualTo(0)
    }

    @Test
    fun `User scope follows membership replay, including revocation`() {
        assertThat(prospectCount(user = "$member", tenants = null)).isEqualTo(1)
        assertThat(prospectCount(user = "$outsider", tenants = null)).isEqualTo(0)
        assertThat(prospectCount(user = "${UUID.randomUUID()}", tenants = null)).isEqualTo(0)
    }

    @Test
    fun `the indirect policy on prospect_event follows the parent prospect`() {
        probe.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                s.execute("select set_config('app.user_id', '$member', true), set_config('app.tenant_ids', null, true)")
            }
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from octo.prospect_event pe join octo.prospect p on p.id = pe.prospect_id " +
                            "where p.tenant_id in ('$tenantA', '$tenantB')",
                    ).use { rs ->
                        rs.next()
                        assertThat(rs.getInt(1)).isEqualTo(1)
                    }
            }
        }
    }

    @Test
    fun `an insert outside the declared Tenants scope is rejected`() {
        probe.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                s.execute("select set_config('app.tenant_ids', '$tenantB', true)")
            }
            assertThatThrownBy {
                c.createStatement().use { s ->
                    s.execute(
                        "insert into octo.prospect (tenant_id, name, source, registered_at, source_system, actor, correlation_id) " +
                            "values ('$tenantA', 'P', 'manual', now(), 'test', 'seeder', gen_random_uuid())",
                    )
                }
            }.isInstanceOf(SQLException::class.java)
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
