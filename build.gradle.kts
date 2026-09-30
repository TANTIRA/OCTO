plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover) apply false
}

buildscript {
    configurations.classpath {
        // CVE-2026-84939: kover's plugin classpath pulls freemarker 2.3.32 via
        // intellij-coverage-reporter -> coverage-report; the project-level force()
        // below cannot reach the build classpath, so pin the patched version here.
        resolutionStrategy.force("org.freemarker:freemarker:2.3.35")
    }
}

allprojects {
    group = "com.octo"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// AGENTS.md: new code in core modules needs at least 70% coverage. Scaffold modules join this
// list when they gain real sources — a module with nothing to cover has nothing to verify.
val coverageEnforcedModules = setOf(
    ":modules:analytics",
    ":modules:api",
    ":modules:control-panel",
    ":modules:ibor-core",
    ":modules:ingestion",
    ":modules:lookthrough",
    ":modules:ontology",
    ":modules:persistence",
    ":modules:workflow",
)

// Kover's reporter (intellij-coverage-reporter 1.0.765) keeps the class-scan filter on a static
// singleton (ClassPathEntry's DirectoryEntryProcessor.setFilter), and Gradle shares one reporter
// classloader across projects. Two modules reporting at once can scan with each other's excludes,
// which dropped the Jdbc* excludes and flaked the gate (#162). Let one Kover task run at a time;
// compilation and tests stay parallel.
abstract class KoverReporterLock : BuildService<BuildServiceParameters.None>

val koverReporterLock =
    gradle.sharedServices.registerIfAbsent("koverReporterLock", KoverReporterLock::class) {
        maxParallelUsages.set(1)
    }

subprojects {

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }

    // The plugin's bundled ktlint predates Kotlin 2.2 and crashes on KtTokens — pin a CLI that
    // supports it.
    pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.8.0")
        }
        configurations.named("ktlint") {
            // Kotlin 2.4 removed kotlin-compiler-embeddable, yet the Kotlin Gradle plugin still
            // rewrites the ktlint worker classpath's request to the project Kotlin version, which
            // then cannot resolve (or start the PSI factory). Re-pin ktlint's own compiler; the
            // afterEvaluate registration makes this substitution run after the KGP's.
            afterEvaluate {
                configurations.named("ktlint") {
                    resolutionStrategy {
                        dependencySubstitution {
                            substitute(module("org.jetbrains.kotlin:kotlin-compiler-embeddable"))
                                .using(module("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21"))
                        }
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        // By name: koverCachedVerify, the task that runs the reporter for koverVerify, has no public type.
        tasks.named { it.startsWith("kover") }.configureEach {
            usesService(koverReporterLock)
        }
        if (project.path in coverageEnforcedModules) {
            extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
                reports {
                    verify {
                        rule("at least 70% line coverage") {
                            minBound(
                                70,
                                kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE,
                                kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE,
                            )
                        }
                    }
                }
            }
            tasks.named("check") {
                dependsOn("koverVerify")
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Testcontainers ITs skip themselves when Docker is down; print the skip instead of a
        // silent green run.
        testLogging {
            events("skipped")
        }
    }
}
