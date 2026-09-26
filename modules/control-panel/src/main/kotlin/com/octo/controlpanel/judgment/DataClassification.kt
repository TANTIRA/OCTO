package com.mesta.asset.controlpanel.judgment

/**
 * Data classes per `AGENTS.md`. Confidential and Strictly Confidential data never leaves the
 * platform for a public model endpoint.
 */
enum class DataClassification {
    Public,
    Internal,
    Confidential,
    StrictlyConfidential,
    ;

    val mayLeavePlatform: Boolean
        get() = this == Public || this == Internal
}

/**
 * State paired with its classification. A decision request cannot be built without declaring one.
 */
data class ClassifiedState(
    val classification: DataClassification,
    val payload: Any,
)
