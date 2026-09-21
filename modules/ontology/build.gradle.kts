plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.jena.shacl)
    implementation(libs.jena.arq)

    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    systemProperty("ontology.dir", rootProject.file("ontology").absolutePath)
}
