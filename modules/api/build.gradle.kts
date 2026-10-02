plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("com.fasterxml.jackson:jackson-bom:2.22.3")
        // The catalog pins micrometer-core / registry-prometheus at 1.17.1, ahead of the
        // micrometer the Boot 3.5.16 BOM manages. Without this import the actuator stack loads a
        // mixed 1.17.1/1.15.12 set and every Spring context dies constructing a controller with
        // NoSuchMethodError: WarnThenDebugLogger.isEnabled(). Importing the Micrometer BOM at the
        // catalog version makes the whole stack agree; this is what takes :modules:api:test from
        // 103 failures to 0.
        mavenBom("io.micrometer:micrometer-bom:${libs.versions.micrometer.get()}")
        // Dependabot #35 / #110: the Boot 3.5.16 BOM pins OpenTelemetry 1.49.0 for the tracing bridge
        // and OTLP exporter (#306), in range for GHSA-rcgg-9c38-7xpx (unbounded W3C baggage
        // allocation; fixed in 1.62.0). The BOM moves api, sdk, exporters and propagators together.
        mavenBom("io.opentelemetry:opentelemetry-bom:${libs.versions.opentelemetry.get()}")
    }
    // Advisory floors the Spring Boot 3.5.16 BOM still sits below. These must live here, not in
    // resolutionStrategy.force(): io.spring.dependency-management registers its BOM pinning as a
    // resolutionStrategy.eachDependency action in afterEvaluate, and Gradle applies eachDependency
    // after force() — so a force() for a BOM-managed module is silently discarded.
    dependencies {
        val netty = libs.versions.netty.get()
        listOf("netty-common", "netty-handler", "netty-buffer", "netty-transport", "netty-codec", "netty-resolver")
            .forEach { dependency("io.netty:$it:$netty") }
        val tomcat = libs.versions.tomcat.get()
        listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket")
            .forEach { dependency("org.apache.tomcat.embed:$it:$tomcat") }
        val log4j = libs.versions.log4j.get()
        listOf("log4j-api", "log4j-to-slf4j").forEach { dependency("org.apache.logging.log4j:$it:$log4j") }
        dependency("org.apache.commons:commons-lang3:3.20.0")
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
    // Traces to the OTEL collector (#306). Export is on only when MANAGEMENT_OTLP_TRACING_ENDPOINT is set
    // (deploy/dokploy.compose.yml derives it from OTEL_EXPORTER_OTLP_ENDPOINT); Apache-2.0.
    implementation(libs.micrometer.tracing.otel)
    runtimeOnly(libs.opentelemetry.exporter.otlp)
    implementation(libs.micrometer.core)
    // Graph projection writer (ADR-0004 amendment, #308); Apache-2.0.
    implementation(libs.neo4j.driver)
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

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.jackson.datatype.jsr310)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.archunit.junit5)
}

// The api suite boots a full Spring context per endpoint test; Gradle's 512m test-JVM default runs
// out of heap partway through (OutOfMemoryError in context refresh). org.gradle.jvmargs sizes the
// daemon, not this forked JVM.
tasks.test {
    maxHeapSize = "2g"
    // GraphProjectionIT applies the canonical Cypher schema to its Neo4j container.
    systemProperty("ontology.dir", rootProject.file("ontology").absolutePath)
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
