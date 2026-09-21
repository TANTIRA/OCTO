plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.kotlin.test)
}
