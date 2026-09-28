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
 * V30's entity scoping against the real schema: stamped tenant_id on the pre-V9 fact tables and
 * derived tenancy through tracked_address on the onchain child tables — exercised as the same
 * non-superuser `rls_probe` RowLevelSecurityIT uses, because the container login owns the objects
 * and bypasses RLS. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class EntityScopingIT {
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val member = UUID.randomUUID()

    // Hyphen-stripped UUIDs are 32 chars; '0' is the only hex char outside base58 — swap it
    // and every wallet is unique per test run while matching the tracked_address shape check.
    private val walletA =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .replace('0', '1')
    private val walletOther =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .replace('0', '1')
    private val walletUntracked =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .replace('0', '1')

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
        DriverManagerDataSource(postgres.jdbcUrl, "rls_probe", "rls_probe")
    }

    @BeforeEach
    fun seed() {
        owner.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                s.execute(
                    "do \$\$ begin " +
                        "if exists (select 1 from pg_roles where rolname = 'rls_probe') then " +
                        "drop owned by rls_probe cascade; drop role rls_probe; " +
                        "end if; end \$\$",
                )
                s.execute("create role rls_probe login password 'rls_probe'")
                s.execute("grant usage on schema octo to rls_probe")
                s.execute(
                    "grant select on octo.ledger_event, octo.onchain_transfer, " +
                        "octo.tracked_address to rls_probe",
                )

                s.execute(
                    "insert into octo.tenant (id, slug, display_name, source_system, correlation_id) " +
                        "values ('$tenantA', 't-${tenantA.toString().take(8)}', 'T', 'test', gen_random_uuid()), " +
                        "('$tenantB', 't-${tenantB.toString().take(8)}', 'TB', 'test', gen_random_uuid())",
                )
                s.execute(
                    "insert into octo.tenant_member (tenant_id, user_id, created_at, source_system, correlation_id) " +
                        "values ('$tenantA', '$member', now(), 'test', gen_random_uuid())",
                )
                s.execute(
                    "insert into octo.tenant_member_event " +
                        "(tenant_id, user_id, event_type, role, actor, occurred_at, correlation_id) " +
                        "values ('$tenantA', '$member', 'granted', 'analyst', '${UUID.randomUUID()}', now(), gen_random_uuid())",
                )

                // Stamped rows: one in tenant A, one in the V30 house tenant, one platform-shared.
                // ingestion_run_id tags this test's rows — earlier tests' rows share the amounts.
                s.execute(
                    "insert into octo.ledger_event (flow_type, monetary_amount, currency_code, occurred_at, " +
                        "tenant_id, source_system, actor, ingestion_run_id, correlation_id) values " +
                        "('contribution', -100, 'USD', now(), '$tenantA', 'test', 'it', '$runId', gen_random_uuid()), " +
                        "('contribution', -200, 'USD', now(), (select id from octo.tenant " +
                        "where slug = 'octo-ops'), 'test', 'it', '$runId', gen_random_uuid()), " +
                        "('contribution', -300, 'USD', now(), null, 'test', 'it', '$runId', gen_random_uuid())",
                )

                // Watches: tenant A tracks walletA, tenant B tracks walletOther; walletUntracked has no row.
                s.execute(
                    "insert into octo.tracked_address (chain, address, tenant_id, source_system, correlation_id) values " +
                        "('solana', '$walletA', '$tenantA', 'test', gen_random_uuid()), " +
                        "('solana', '$walletOther', '$tenantB', 'test', gen_random_uuid())",
                )
                // Derived rows on each wallet.
                s.execute(
                    "insert into octo.onchain_transfer (external_id, chain, signature, slot, block_time, " +
                        "commitment, wallet, amount_raw, decimals, direction, transfer_kind, source_system, " +
                        "actor, ingestion_run_id, correlation_id) values " +
                        "('x-${UUID.randomUUID()}', 'solana', 'sig-a', 1, now(), 'finalized', " +
                        "'$walletA', 1, 0, 'in', 'transfer-in', 'test', 'it', gen_random_uuid(), gen_random_uuid()), " +
                        "('x-${UUID.randomUUID()}', 'solana', 'sig-b', 2, now(), 'finalized', " +
                        "'$walletOther', 1, 0, 'in', 'transfer-in', 'test', 'it', gen_random_uuid(), gen_random_uuid()), " +
                        "('x-${UUID.randomUUID()}', 'solana', 'sig-u', 3, now(), 'finalized', " +
                        "'$walletUntracked', 1, 0, 'in', 'transfer-in', 'test', 'it', gen_random_uuid(), gen_random_uuid())",
                )
            }
            c.commit()
        }
    }

    private fun scopedCount(
        sql: String,
        user: String? = null,
        tenants: String? = null,
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
                s.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private val runId = UUID.randomUUID()
    private val ledgerRows = "select count(*) from octo.ledger_event where ingestion_run_id = '$runId'"
    private val transferRows =
        "select count(*) from octo.onchain_transfer where wallet in ('$walletA', '$walletOther', '$walletUntracked')"

    @Test
    fun `the V30 house tenant exists for the backfilled rows`() {
        owner.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from octo.tenant where slug = 'octo-ops'").use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `stamped tables follow membership — other tenants and platform rows stay hidden`() {
        // Member of A sees only the A row; octo-ops and platform-shared rows are invisible.
        assertThat(scopedCount(ledgerRows, user = "$member")).isEqualTo(1)
        assertThat(scopedCount(ledgerRows, user = "${UUID.randomUUID()}")).isEqualTo(0)
        assertThat(scopedCount(ledgerRows, tenants = "$tenantA")).isEqualTo(1)
    }

    @Test
    fun `platform scan sees stamped rows including the NULL-tenant one`() {
        assertThat(scopedCount(ledgerRows, tenants = "*")).isEqualTo(3)
    }

    @Test
    fun `derived tenancy follows tracked_address — untracked wallets are platform rows`() {
        assertThat(scopedCount(transferRows, user = "$member")).isEqualTo(1)
        assertThat(scopedCount(transferRows, tenants = "$tenantA")).isEqualTo(1)
        assertThat(scopedCount(transferRows, tenants = "*")).isEqualTo(3)
    }

    @Test
    fun `the append-only trigger still rejects updates after the backfill`() {
        // Run as owner: the probe holds select only, and the trigger — not the grant — is under test.
        owner.connection.use { c ->
            assertThatThrownBy {
                c.createStatement().use { s ->
                    s.execute("update octo.ledger_event set monetary_amount = -101 where monetary_amount = -100")
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
