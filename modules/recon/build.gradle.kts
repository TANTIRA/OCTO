plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Break details are stored as jsonb; Apache-2.0, the same artifact ingestion already uses.
    implementation(libs.jackson.databind)

    testImplementation(libs.kotlin.test)
}

// JdbcReconciliationStore is thin JDBC glue exercised end-to-end by ReconciliationStoreIT in :modules:api (same as ingestion).
kover {
    reports {
        filters {
            excludes {
                classes("com.mesta.asset.recon.matching.persistence.*")
            }
        }
    }
}
