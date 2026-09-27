plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.kotlin.test)
}

// JdbcProspectStore is JDBC glue exercised end-to-end by ProspectStoreIT in :modules:api, the same
// arrangement as workflow's task/report stores: coverage is attributed per module, so it would
// otherwise read 0% here.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.octo.dealsourcing.persistence.*",
                )
            }
        }
    }
}
