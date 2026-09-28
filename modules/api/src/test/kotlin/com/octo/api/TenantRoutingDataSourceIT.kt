package com.octo.api

import com.octo.persistence.TenantRoutingContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException

/**
 * `TenantRoutingDataSource` (ADR-0007) against two real databases: a bound context routes to the
 * keyed target, an unbound request stays on the shared pool, and an unknown key degrades to the
 * pool rather than erroring. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantRoutingDataSourceIT {
    private val pool = DriverManagerDataSource(postgresA.jdbcUrl, postgresA.username, postgresA.password)
    private val bridge = DriverManagerDataSource(postgresB.jdbcUrl, postgresB.username, postgresB.password)
    private val routing = TenantRoutingDataSource(pool, mapOf("bridge-b" to bridge))

    @Test
    fun `a bound context reaches the keyed database, the pool by default, and on a stale key`() {
        // Marker exists only on B: `select * from isolation_marker` is the which-DB probe.
        bridge.connection.use { c ->
            c.createStatement().use { it.execute("create table isolation_marker (id int)") }
        }

        // Unbound → pool (A): no marker table.
        assertThatThrownBy { probe(routing) }.isInstanceOf(SQLException::class.java)

        // Bound → bridge (B): marker reads.
        TenantRoutingContext.within("bridge-b") {
            assertThat(probe(routing)).isTrue
        }

        // Binding unwinds — the next unbound read is the pool again.
        assertThatThrownBy { probe(routing) }.isInstanceOf(SQLException::class.java)

        // A key no registry knows degrades to the pool, never errors open.
        TenantRoutingContext.within("nobody-registered-this") {
            assertThatThrownBy { probe(routing) }.isInstanceOf(SQLException::class.java)
        }

        // Nested scopes restore the outer binding in order.
        TenantRoutingContext.within("bridge-b") {
            TenantRoutingContext.within("nobody-registered-this") {
                assertThatThrownBy { probe(routing) }
            }
            assertThat(probe(routing)).isTrue
        }
    }

    private fun probe(source: TenantRoutingDataSource): Boolean =
        source.connection.use { c ->
            c.createStatement().use { s -> s.execute("select 1 from isolation_marker") }
        }

    companion object {
        @Container
        @JvmStatic
        val postgresA = PostgreSQLContainer("postgres:17-alpine")

        @Container
        @JvmStatic
        val postgresB = PostgreSQLContainer("postgres:17-alpine")
    }
}
