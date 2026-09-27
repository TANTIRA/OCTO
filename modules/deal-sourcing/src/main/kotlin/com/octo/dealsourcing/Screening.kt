package com.octo.dealsourcing

/**
 * One tenant's screening criteria (`mesta.screening_rule.criteria`, V20). Every field is an allowed
 * set; absent means unconstrained. The shape stays deliberately small — the same criteria govern the
 * deterministic evaluator today and bound whatever an AI screener may conclude later.
 */
data class ScreeningCriteria(
    val sectors: Set<String>? = null,
    val regions: Set<String>? = null,
    val sources: Set<ProspectSource>? = null,
) {
    companion object {
        /**
         * Reads the persisted document once JSON is flattened at the edge:
         * `{"sectors": […], "regions": […], "sources": […]}` → field → allowed values. Unknown keys
         * are ignored so the document can grow; malformed source names fail closed by throwing.
         */
        fun parse(fields: Map<String, List<String>>): ScreeningCriteria =
            ScreeningCriteria(
                sectors = fields["sectors"]?.toSet(),
                regions = fields["regions"]?.toSet(),
                sources = fields["sources"]?.map { ProspectSource.fromWireValue(it) }?.toSet(),
            )
    }
}

/** What a screen can conclude. REVIEW means data was missing; REJECT means data was present and failed. */
enum class ScreeningVerdict {
    CLEAR,
    REVIEW,
    REJECT,
}

/** The verdict plus the reasons a person — or an auditor — needs to see. */
data class ScreeningOutcome(
    val verdict: ScreeningVerdict,
    val reasons: List<String>,
)

/**
 * Evaluates [prospect] against [criteria] with no discretion: a violated constraint rejects, a
 * missing field sends to review (a screener cannot pass what it cannot see), otherwise clear.
 * REJECT wins over REVIEW — a hard failure is never softened by missing data elsewhere.
 */
fun ScreeningCriteria.evaluate(prospect: Prospect): ScreeningOutcome {
    val rejected = mutableListOf<String>()
    val review = mutableListOf<String>()
    check(sectors, prospect.sector, "sector", rejected, review)
    check(regions, prospect.region, "region", rejected, review)
    check(sources?.map { it.wireValue }?.toSet(), prospect.source.wireValue, "source", rejected, review)
    val verdict =
        when {
            rejected.isNotEmpty() -> ScreeningVerdict.REJECT
            review.isNotEmpty() -> ScreeningVerdict.REVIEW
            else -> ScreeningVerdict.CLEAR
        }
    return ScreeningOutcome(verdict, rejected + review)
}

private fun check(
    allowed: Set<String>?,
    actual: String?,
    field: String,
    rejected: MutableList<String>,
    review: MutableList<String>,
) {
    if (allowed == null) return
    when {
        actual == null -> review += "no $field declared"
        actual !in allowed -> rejected += "$field '$actual' is outside the mandate $allowed"
    }
}
