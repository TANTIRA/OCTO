package com.octo.controlpanel.judgment

/** What an approved model may be used for: a judge scores, a drafter writes prose (ADR-0005). */
enum class ModelRole { JUDGE, DRAFTER }

/**
 * A model cleared to receive platform data, with the two facts governance needs: whether its
 * provider endpoint is zero-data-retention (ADR-0005, `docs/data-security-governance.md`) and the
 * role it is approved for.
 */
data class ApprovedModel(
    val id: String,
    val zeroDataRetention: Boolean,
    val role: ModelRole,
) {
    init {
        require(id.isNotBlank()) { "model id is required" }
    }
}

/** Raised when a configured model is not on the registry or is not cleared for the requested use. */
class ModelNotApprovedException(
    val modelId: String,
    reason: String,
) : IllegalStateException("model '$modelId' is not approved: $reason")

/**
 * The set of models cleared to receive platform data. Enforcement is fail-closed: [require] returns
 * only for a model that is registered, marked zero-data-retention, and approved for the role the
 * caller needs — anything else throws before a request leaves the process. Approving a model is a
 * governance action (ADR-0005), so the registry is a small, code-reviewed list, never an open toggle.
 */
class ModelRegistry(
    models: List<ApprovedModel>,
) {
    private val byId: Map<String, ApprovedModel> = models.associateBy { it.id }

    init {
        require(models.isNotEmpty()) { "the registry needs at least one approved model" }
        require(byId.size == models.size) { "duplicate model id in the registry" }
    }

    /**
     * Returns the approved model for [modelId] in [role], or throws [ModelNotApprovedException]. A
     * registered but non-ZDR model is rejected too, so confidential-adjacent data never reaches an
     * endpoint that may retain it.
     */
    fun require(
        modelId: String,
        role: ModelRole,
    ): ApprovedModel {
        val model = byId[modelId] ?: throw ModelNotApprovedException(modelId, "not on the approved-model registry")
        if (!model.zeroDataRetention) throw ModelNotApprovedException(modelId, "endpoint is not zero-data-retention")
        if (model.role != role) throw ModelNotApprovedException(modelId, "approved for ${model.role}, not $role")
        return model
    }

    companion object {
        /**
         * The built-in registry: `typesafe/jev-1.13` is the ZDR-listed judge (issue #6) and the default
         * decision model. A drafter is added here — as a reviewed change — when the drafting slice lands.
         */
        val DEFAULT = ModelRegistry(listOf(ApprovedModel("typesafe/jev-1.13", zeroDataRetention = true, role = ModelRole.JUDGE)))
    }
}
