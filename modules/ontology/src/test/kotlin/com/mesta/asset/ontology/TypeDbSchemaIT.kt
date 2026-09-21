package com.mesta.asset.ontology

import com.typedb.driver.TypeDB
import com.typedb.driver.api.Credentials
import com.typedb.driver.api.DriverOptions
import com.typedb.driver.api.DriverTlsConfig
import com.typedb.driver.api.Transaction
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the canonical `ontology/mesta-investment.tql` actually applies to a real TypeDB server —
 * a .tql file that parses for the drift guard but fails `define` (reserved labels, bad ownership,
 * circular subs) would otherwise only surface at first deployment.
 *
 * Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class TypeDbSchemaIT {
    private companion object {
        // TypeDB Community Edition: default credentials are admin/password.
        @Container
        @JvmStatic
        val typedb =
            GenericContainer(DockerImageName.parse("typedb/typedb:3.12.3"))
                .withExposedPorts(1729)
    }

    @Test
    fun `the canonical TypeQL schema defines cleanly on a real TypeDB`() {
        val schema =
            File(
                System.getProperty("ontology.dir"),
                "mesta-investment.tql",
            ).readText()
        val address = "${typedb.host}:${typedb.getMappedPort(1729)}"

        TypeDB
            .driver(
                address,
                Credentials("admin", "password"),
                DriverOptions(DriverTlsConfig.disabled()),
            ).use { driver ->
                driver.databases().create("mesta-schema-it")

                driver.transaction("mesta-schema-it", Transaction.Type.SCHEMA).use { tx ->
                    tx.query(schema).resolve()
                    tx.commit()
                }

                driver.transaction("mesta-schema-it", Transaction.Type.READ).use { tx ->
                    // 'entity'/'thing' are reserved in TypeQL 3, so verify through a real label:
                    // organization has subtypes in the schema (fund-manager, limited-partner, …).
                    val organizationSubtypes =
                        tx
                            .query("match \$t sub organization;")
                            .resolve()
                            .asConceptRows()
                            .stream()
                            .count()
                    assertTrue(organizationSubtypes > 0, "schema define committed but no subtypes are visible")
                }
            }
    }
}
