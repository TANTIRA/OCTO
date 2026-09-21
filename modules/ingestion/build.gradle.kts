plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":modules:control-panel"))
    implementation(libs.jackson.databind)

    testImplementation(libs.kotlin.test)
}

// JdbcDecisionStore is thin JDBC glue exercised end-to-end by DecisionStoreIT in :modules:api.
// Coverage attribution is per-module, so without this filter the class reads 0% and would drag
// an otherwise fully unit-tested module below the 70% gate.
kover {
    reports {
        filters {
            excludes {
                classes("com.mesta.asset.ingestion.persistence.*")
            }
        }
    }
}

val ontologySchema = rootProject.file("ontology/mesta-investment.tql")

tasks.withType<Test> {
    systemProperty("ontology.file", ontologySchema.absolutePath)
    inputs
        .file(ontologySchema)
        .withPropertyName("ontologySchema")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
