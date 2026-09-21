plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.jena.shacl)
    implementation(libs.jena.arq)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.typedb.driver)
    testImplementation(libs.testcontainers.junit)
}

tasks.withType<Test> {
    systemProperty("ontology.dir", rootProject.file("ontology").absolutePath)
    // The tests read ontology/*.tql and *.ttl directly — without this they go UP-TO-DATE stale.
    inputs.dir(rootProject.file("ontology"))
}
