plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.jena.shacl)
    implementation(libs.jena.arq)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.neo4j.driver)
    testImplementation(libs.testcontainers.junit)
}

tasks.withType<Test> {
    systemProperty("ontology.dir", rootProject.file("ontology").absolutePath)
    // The tests read ontology/*.cypher and *.ttl directly — without this they go UP-TO-DATE stale.
    inputs.dir(rootProject.file("ontology"))
}
