plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":modules:control-panel"))
    implementation(libs.jackson.databind)

    testImplementation(libs.kotlin.test)
}

val ontologySchema = rootProject.file("ontology/mesta-investment.tql")

tasks.withType<Test> {
    systemProperty("ontology.file", ontologySchema.absolutePath)
    inputs.file(ontologySchema)
        .withPropertyName("ontologySchema")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
