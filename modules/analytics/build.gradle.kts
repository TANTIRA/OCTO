plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":modules:persistence"))

    testImplementation(libs.kotlin.test)
}

// JdbcModelRunStore is thin JDBC glue covered by ModelRunStoreIT in :modules:api (same as ibor-core).
kover {
    reports {
        filters {
            excludes {
                classes("com.octo.analytics.persistence.*")
            }
        }
    }
}
