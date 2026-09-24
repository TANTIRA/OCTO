plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
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
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    // Prometheus scrape endpoint for docs/reliability.md; Apache-2.0, version from the Boot BOM.
    runtimeOnly(libs.micrometer.prometheus)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.rs)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.archunit.junit5)
}

tasks.processResources {
    from(rootProject.file("db/migrations")) {
        exclude("README.md")
        into("db/migration")
    }
}

// No Flyway Gradle plugin: 11.7.2 calls JavaPluginConvention, which Gradle 9 removed, so every
// flyway* task fails before it can run. Migrations execute at application boot through Spring
// Boot Flyway (application.yml); for an ad-hoc run use the `flyway` CLI against db/migrations
// with the DB_MIGRATION_* credentials and -placeholders.runtime_role=$DB_USER.
