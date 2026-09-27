package com.octo.api

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Enforces the module dependency matrix declared in AGENTS.md's layout table: `modules/api` is
 * the only composition root, so nothing may depend on it, and domain modules may not reach into
 * each other outside the allowlist below.
 *
 * Adding a legitimate dependency means updating [allowedDependencies] in the same commit — that
 * is the review checkpoint, not a bug to work around.
 */
class ModuleBoundaryTest {
    private val modulePackages =
        mapOf(
            "analytics" to "com.octo.analytics",
            "api" to "com.octo.api",
            "control-panel" to "com.octo.controlpanel",
            "deal-sourcing" to "com.octo.dealsourcing",
            "ibor-core" to "com.octo.iborcore",
            "ingestion" to "com.octo.ingestion",
            "lookthrough" to "com.octo.lookthrough",
            "ontology" to "com.octo.ontology",
            "recon" to "com.octo.recon",
            "workflow" to "com.octo.workflow",
        )

    /** What each module is allowed to reach. api composes everything; everyone else is closed. */
    private val allowedDependencies =
        mapOf(
            "api" to modulePackages.keys - "api",
            "ingestion" to setOf("control-panel"),
            // #106: compliance rules evaluate the look-through exposure (#10) and coverage ratio (#45) as given.
            "recon" to setOf("ibor-core", "analytics", "lookthrough"),
        )

    private val productionClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.octo")

    @Test
    fun `module dependencies stay inside the declared matrix`() {
        for ((module, pkg) in modulePackages) {
            val forbidden =
                modulePackages
                    .filterKeys { it != module && it !in allowedDependencies[module].orEmpty() }
                    .values
                    .map { "$it.." }
                    .toTypedArray()

            noClasses()
                .that()
                .resideInAPackage("$pkg..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(*forbidden)
                .`as`("$module must not depend on modules outside its allowlist")
                .allowEmptyShould(true) // scaffold modules have no classes yet
                .check(productionClasses)
        }
    }

    @Test
    fun `recon keeps JDBC inside persistence packages`() {
        noClasses()
            .that()
            .resideInAPackage("com.octo.recon..")
            .and()
            .resideOutsideOfPackages("com.octo.recon..persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..")
            .`as`(
                "recon compares fetched rows in pure functions; only *.persistence readers open connections and nothing writes a correction",
            ).allowEmptyShould(true)
            .check(productionClasses)
    }

    @Test
    fun `helius vendor types stay inside the ingestion module`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("com.octo.ingestion..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.octo.ingestion.onchain.helius..")
            .`as`("vendor payload shapes stop inside the helius adapter package — ADR-0001")
            .check(productionClasses)
    }

    @Test
    fun `evm vendor types stay inside the ingestion module`() {
        noClasses()
            .that()
            .resideOutsideOfPackages("com.octo.ingestion..", "com.octo.api.ingestion..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.octo.ingestion.onchain.evm..")
            .`as`("chain-adapter internals stop inside the evm package; api.ingestion is the composition root — ADR-0001")
            .check(productionClasses)
    }

    @Test
    fun `alphavantage vendor types stay inside the ingestion module`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("com.octo.ingestion..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.octo.ingestion.marketdata.alphavantage..")
            .`as`("vendor payload shapes stop inside the alphavantage adapter package — ADR-0001")
            .check(productionClasses)
    }
}
