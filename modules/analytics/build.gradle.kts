plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.kotlin.test)
}

// JdbcModelRunStore is thin JDBC glue covered by ModelRunStoreIT in :modules:api (same as ibor-core).
kover {
    reports {
        filters {
            excludes {
                classes("com.mesta.asset.analytics.persistence.*")
            }
        }
    }
}
