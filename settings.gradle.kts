plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "octo"

include(
    "modules:api",
    "modules:analytics",
    "modules:control-panel",
    "modules:deal-sourcing",
    "modules:ibor-core",
    "modules:ingestion",
    "modules:lookthrough",
    "modules:ontology",
    "modules:persistence",
    "modules:recon",
    "modules:workflow",
)
