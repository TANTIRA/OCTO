plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":modules:ibor-core"))

    testImplementation(libs.kotlin.test)
}
