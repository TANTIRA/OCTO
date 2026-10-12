plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.jena.shacl)
    implementation(libs.jena.arq)

    // Jena pulls libthrift 0.22.0 (advisory floor 0.24.0) and Neo4j's Bolt transport pulls
    // netty-handler 4.2.15.Final (floor 4.2.17.Final). Netty 4.2 only here — the api module is on
    // the 4.1 line that reactor-netty requires.
    constraints {
        implementation(libs.thrift)
        testImplementation("io.netty:netty-handler:4.1.139.Final")
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.neo4j.driver)
    testImplementation(libs.testcontainers.junit)
}

tasks.withType<Test> {
    systemProperty("ontology.dir", rootProject.file("ontology").absolutePath)
    // The tests read ontology/*.cypher and *.ttl directly — without this they go UP-TO-DATE stale.
    inputs.dir(rootProject.file("ontology"))
}
