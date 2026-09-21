plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    alias(libs.plugins.flyway)
}

dependencies {
    implementation(project(":modules:analytics"))
    implementation(project(":modules:control-panel"))
    implementation(project(":modules:deal-sourcing"))
    implementation(project(":modules:ibor-core"))
    implementation(project(":modules:ingestion"))
    implementation(project(":modules:lookthrough"))
    implementation(project(":modules:recon"))
    implementation(project(":modules:workflow"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
}

tasks.processResources {
    from(rootProject.file("db/migrations")) {
        exclude("README.md")
        into("db/migration")
    }
}

flyway {
    url = "jdbc:postgresql://${System.getenv("DB_HOST") ?: "localhost"}:${System.getenv("DB_PORT") ?: "5432"}/${System.getenv("DB_NAME") ?: "postgres"}"
    user = System.getenv("DB_MIGRATION_USER") ?: ""
    password = System.getenv("DB_MIGRATION_PASSWORD") ?: ""
    locations = arrayOf("filesystem:${rootProject.file("db/migrations")}")
}

tasks.named("flywayMigrate") {
    doFirst {
        require(!System.getenv("DB_MIGRATION_USER").isNullOrBlank()) { "DB_MIGRATION_USER is required" }
        require(!System.getenv("DB_MIGRATION_PASSWORD").isNullOrBlank()) { "DB_MIGRATION_PASSWORD is required" }
    }
}

tasks.named("flywayClean") {
    enabled = false
}
