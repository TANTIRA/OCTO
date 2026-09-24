plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.kotlin.test)
}

// JdbcAuditLog is JDBC glue exercised end-to-end by AuditLogIT in :modules:api, the same arrangement as
// ingestion's decision store: coverage is attributed per module, so it would otherwise read 0% here.
kover {
    reports {
        filters {
            excludes {
                classes("com.mesta.asset.workflow.audit.JdbcAuditLog")
            }
        }
    }
}
