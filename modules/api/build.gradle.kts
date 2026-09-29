plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

configurations.all {
    resolutionStrategy {
        // Force patched versions for CVE-2024/2025 vulnerabilities (transitive deps from Spring Boot BOM)
        force("io.netty:netty-common:4.1.137.Final")
        force("io.netty:netty-handler:4.1.137.Final")
        force("io.netty:netty-buffer:4.1.137.Final")
        force("io.netty:netty-transport:4.1.137.Final")
        force("io.netty:netty-codec:4.1.137.Final")
        force("io.netty:netty-resolver:4.1.137.Final")
        force("org.apache.tomcat.embed:tomcat-embed-core:10.1.60")
        force("org.apache.tomcat.embed:tomcat-embed-el:10.1.60")
        force("org.apache.tomcat.embed:tomcat-embed-websocket:10.1.60")
        force("org.apache.logging.log4j:log4j-api:2.25.5")
        force("org.apache.logging.log4j:log4j-to-slf4j:2.25.5")
        force("org.freemarker:freemarker:2.3.35")
        force("org.bouncycastle:bcprov-jdk18on:1.85")
        force("org.apache.thrift:libthrift:0.24.0")
        force("org.apache.httpcomponents.client5:httpclient5:5.6.3")
        force("org.apache.httpcomponents.core5:httpcore5:5.4.3")
        force("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
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

    // Force patched versions for CVE-2024/2025 vulnerabilities (transitive deps from Spring Boot BOM)
    // Using strictly() to override Spring Dependency Management BOM
    implementation("io.netty:netty-common:4.1.137.Final") { version { strictly("4.1.137.Final") } }
    implementation("io.netty:netty-handler:4.1.137.Final") { version { strictly("4.1.137.Final") } }
    implementation("io.netty:netty-buffer:4.1.137.Final") { version { strictly("4.1.137.Final") } }
    implementation("io.netty:netty-transport:4.1.137.Final") { version { strictly("4.1.137.Final") } }
    implementation("io.netty:netty-codec:4.1.137.Final") { version { strictly("4.1.137.Final") } }
    implementation("io.netty:netty-resolver:4.1.137.Final") { version { strictly("4.1.137.Final") } }
    implementation("org.apache.tomcat.embed:tomcat-embed-core:10.1.60") { version { strictly("10.1.60") } }
    implementation("org.apache.tomcat.embed:tomcat-embed-el:10.1.60") { version { strictly("10.1.60") } }
    implementation("org.apache.tomcat.embed:tomcat-embed-websocket:10.1.60") { version { strictly("10.1.60") } }
    implementation("org.apache.logging.log4j:log4j-api:2.25.5") { version { strictly("2.25.5") } }
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.25.5") { version { strictly("2.25.5") } }
    implementation("org.freemarker:freemarker:2.3.35") { version { strictly("2.3.35") } }
    implementation("org.bouncycastle:bcprov-jdk18on:1.85") { version { strictly("1.85") } }
    implementation("org.apache.thrift:libthrift:0.24.0") { version { strictly("0.24.0") } }
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.3") { version { strictly("5.6.3") } }
    implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3") { version { strictly("5.4.3") } }
    implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3") { version { strictly("5.4.3") } }

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
