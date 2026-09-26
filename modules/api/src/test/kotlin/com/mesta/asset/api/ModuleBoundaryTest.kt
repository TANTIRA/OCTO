package com.mesta.asset.api

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
            "analytics" to "com.mesta.asset.analytics",
            "api" to "com.mesta.asset.api",
            "control-panel" to "com.mesta.asset.controlpanel",
            "deal-sourcing" to "com.mesta.asset.dealsourcing",
            "ibor-core" to "com.mesta.asset.iborcore",
            "ingestion" to "com.mesta.asset.ingestion",
            "lookthrough" to "com.mesta.asset.lookthrough",
            "ontology" to "com.mesta.asset.ontology",
            "recon" to "com.mesta.asset.recon",
            "workflow" to "com.mesta.asset.workflow",
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
            .importPackages("com.mesta.asset")

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
    fun `recon is reporting-only - no JDBC or DataSource references`() {
        noClasses()
            .that()
            .resideInAPackage("com.mesta.asset.recon..")
            .and()
            .resideOutsideOfPackages("com.mesta.asset.recon..persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..")
            .`as`("recon domain compares fetched rows; only its persistence adapters may open a connection")
            .allowEmptyShould(true)
            .check(productionClasses)
    }

    @Test
    fun `helius vendor types stay inside the ingestion module`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("com.mesta.asset.ingestion..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.mesta.asset.ingestion.onchain.helius..")
            .`as`("vendor payload shapes stop inside the helius adapter package — ADR-0001")
            .check(productionClasses)
    }
}
