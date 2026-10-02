package com.octo.ontology

import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.exceptions.ClientException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the canonical `ontology/octo-investment.cypher` actually applies to a real Neo4j
 * server — a constraint that references a misspelled label or property would otherwise only
 * surface at first deployment. Mirrors the contract TypeDbSchemaIT gave for TypeQL.
 *
 * Runs against the same image tag deployed on Dokploy (neo4j:2025.12.1-community); skipped
 * automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class Neo4jSchemaIT {
    private companion object {
        private const val PASSWORD = "octo-schema-it-password"

        @Container
        @JvmStatic
        val neo4j =
            GenericContainer(DockerImageName.parse("neo4j:2025.12.1-community"))
                .withEnv("NEO4J_AUTH", "neo4j/$PASSWORD")
                .withExposedPorts(7687)

        /** Executable statements from the dual-format file: non-comment, non-blank text split on `;`. */
        fun executableStatements(schema: String): List<String> =
            schema
                .lines()
                .filter {
                    val l = it.trim()
                    l.isNotEmpty() && !l.startsWith("//")
                }.joinToString("\n")
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }

    @Test
    fun `the canonical Cypher schema defines constraints cleanly on a real Neo4j`() {
        val schema =
            File(
                System.getProperty("ontology.dir"),
                "octo-investment.cypher",
            ).readText()
        val statements = executableStatements(schema)
        assertTrue(statements.all { it.startsWith("CREATE CONSTRAINT") }, "non-constraint statement found in schema")

        val uri = "bolt://${neo4j.host}:${neo4j.getMappedPort(7687)}"
        GraphDatabase.driver(uri, AuthTokens.basic("neo4j", PASSWORD)).use { driver ->
            driver.session().use { session ->
                statements.forEach { session.run(it).consume() }

                val applied =
                    session
                        .run("SHOW CONSTRAINTS YIELD name RETURN collect(name) AS names")
                        .single()["names"]
                        .asList { it.asString() }

                assertEquals(
                    statements.size,
                    applied.size,
                    "every CREATE CONSTRAINT in the schema must be visible after apply",
                )
                assertTrue(applied.any { it == "instrument_instrument_id_key" }, "instrument key constraint missing")

                // Tenant-owned keys are per tenant (ADR-0004, ontology 2.0.0): two tenants may hold the
                // same fund, one tenant may not hold it twice, and an octoId is unique across tenants.
                val a = "00000000-0000-4000-8000-00000000000a"
                val b = "00000000-0000-4000-8000-00000000000b"
                session
                    .run(
                        "CREATE (:Fund {tenantId: '$a', legalName: 'Same Fund LP', octoId: '00000000-0000-4000-8000-000000000001'})",
                    ).consume()
                session
                    .run(
                        "CREATE (:Fund {tenantId: '$b', legalName: 'Same Fund LP', octoId: '00000000-0000-4000-8000-000000000002'})",
                    ).consume()
                assertEquals(2L, session.run("MATCH (n:Fund) RETURN count(n) AS c").single()["c"].asLong())
                assertFailsWith<ClientException> {
                    session
                        .run(
                            "CREATE (:Fund {tenantId: '$a', legalName: 'Same Fund LP', octoId: '00000000-0000-4000-8000-000000000003'})",
                        ).consume()
                }
                assertFailsWith<ClientException> {
                    session
                        .run(
                            "CREATE (:Fund {tenantId: '$b', legalName: 'Other Fund LP', octoId: '00000000-0000-4000-8000-000000000001'})",
                        ).consume()
                }
            }
        }
    }
}
