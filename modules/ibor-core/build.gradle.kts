plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.kotlin.test)
}

val ontologySchema = rootProject.file("ontology/mesta-investment.tql")

tasks.withType<Test> {
    systemProperty("ontology.file", ontologySchema.absolutePath)
    inputs
        .file(ontologySchema)
        .withPropertyName("ontologySchema")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

// JdbcIborReader is thin JDBC glue covered by IborReaderIT in :modules:api (same as ingestion).
kover {
    reports {
        filters {
            excludes {
                classes("com.mesta.asset.iborcore.persistence.*")
            }
        }
    }
}
