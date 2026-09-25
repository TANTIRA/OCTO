plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Break details are stored as jsonb; Apache-2.0, the same artifact ingestion already uses.
    // Rules evaluate the look-through exposure (#10) and the coverage ratio (#45) as given; recon computes neither.
    implementation(project(":modules:analytics"))
    implementation(project(":modules:lookthrough"))
    // Rule definitions are stored as jsonb; Apache-2.0, the same artifact ingestion already uses.
    implementation(libs.jackson.databind)

    testImplementation(libs.kotlin.test)
}

// JdbcReconciliationStore is thin JDBC glue exercised end-to-end by ReconciliationStoreIT in :modules:api (same as ingestion).
// JdbcComplianceStore is thin JDBC glue exercised end-to-end by ComplianceStoreIT in :modules:api (same as ingestion).
kover {
    reports {
        filters {
            excludes {
                classes("com.mesta.asset.recon.matching.persistence.*")
                classes("com.mesta.asset.recon.compliance.persistence.*")
            }
        }
    }
}
