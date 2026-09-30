plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("com.fasterxml.jackson:jackson-bom:2.22.3")
    }
}

dependencies {
    implementation(project(":modules:control-panel"))
    implementation(project(":modules:persistence"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    // Enforce Jackson BOM 2.22.3 over Spring Boot's 2.21.5 for CVE fixes
    constraints {
        add("implementation", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    }

    testImplementation(libs.kotlin.test)
}

// JdbcDecisionStore and JdbcOnchainStagingStore are thin JDBC glue exercised end-to-end by
// DecisionStoreIT and OnchainStagingStoreIT in :modules:api. Coverage attribution is
// per-module, so without this filter the classes read 0% and would drag an otherwise fully
// unit-tested module below the 70% gate.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.octo.ingestion.persistence.*",
                    "com.octo.ingestion.onchain.persistence.*",
                )
            }
        }
    }
}

val ontologySchema = rootProject.file("ontology/octo-investment.cypher")

tasks.withType<Test> {
    systemProperty("ontology.file", ontologySchema.absolutePath)
    inputs
        .file(ontologySchema)
        .withPropertyName("ontologySchema")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // The live decision-model eval runs only with the key. Tracking whether the key is present (never its value)
    // stops a cached run from when the eval was skipped satisfying a run that must enforce the thresholds.
    inputs.property("liveDecisionEval", System.getenv("OPENROUTER_API_KEY").isNullOrBlank().not())
}
