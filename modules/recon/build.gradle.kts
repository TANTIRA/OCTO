plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Rules evaluate the look-through exposure (#10) and the coverage ratio (#45) as given; recon computes neither.
    implementation(project(":modules:analytics"))
    implementation(project(":modules:lookthrough"))

    testImplementation(libs.kotlin.test)
}
