plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("com.fasterxml.jackson:jackson-bom:2.22.3")
    }
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    // Enforce Jackson BOM 2.22.3 over Spring Boot's 2.21.5 for CVE fixes
    constraints {
        add("implementation", enforcedPlatform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    }

    testImplementation(libs.kotlin.test)
}
