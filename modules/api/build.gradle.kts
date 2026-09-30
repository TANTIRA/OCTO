plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("com.fasterxml.jackson:jackson-bom:2.22.3")
    }
}

configurations.all {
    resolutionStrategy {
        // Advisory floors the Spring Boot 3.5.16 BOM still sits below. Not every alert is a
        // dependency this module really has — the Boot Gradle plugin and the Kotlin plugin carry
        // their own copies on the buildscript classpath, which these cannot reach.
        listOf("netty-common", "netty-handler", "netty-buffer", "netty-transport", "netty-codec", "netty-resolver")
            .forEach { force("io.netty:$it:4.1.138.Final") }
        listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket")
            .forEach { force("org.apache.tomcat.embed:$it:10.1.60") }
        listOf("log4j-api", "log4j-to-slf4j").forEach { force("org.apache.logging.log4j:$it:2.25.5") }
        force("io.opentelemetry:opentelemetry-api:1.62.0")
        force("org.apache.commons:commons-lang3:3.20.0")
    }
}

dependencies {
    implementation(project(":modules:analytics"))
    implementation(project(":modules:control-panel"))
    implementation(project(":modules:deal-sourcing"))
    implementation(project(":modules:ibor-core"))
    implementation(project(":modules:ingestion"))
    implementation(project(":modules:lookthrough"))
    implementation(project(":modules:persistence"))
    implementation(project(":modules:recon"))
    implementation(project(":modules:workflow"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    // Prometheus scrape endpoint for docs/reliability.md; Apache-2.0, version from the Boot BOM.
    runtimeOnly(libs.micrometer.prometheus)
    implementation(libs.micrometer.core)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.rs)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    runtimeOnly(libs.postgresql)

    // Floors the Boot BOM does not manage: testcontainers drags commons-compress 1.24.0.
    constraints {
        testImplementation(libs.commons.compress)
    }

    // Enforce Jackson BOM 2.22.3 over Spring Boot's 2.21.5 for CVE fixes
    constraints {
        add("implementation", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    }

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.jackson.datatype.jsr310)
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
