package com.mesta.asset.ingestion.classification

/**
 * Mirrors the `document-type` attribute in `ontology/mesta-investment.tql`. A drift test fails if
 * the enumeration and the schema diverge.
 */
enum class DocumentType(
    val wireValue: String,
) {
    PITCH_DECK("pitch-deck"),
    DDQ("ddq"),
    FINANCIALS("financials"),
    ICAP_REPORT("icap-report"),
    MEMO("memo"),
    LEGAL("legal"),
    LP_REPORT("lp-report"),
    TEAR_SHEET("tear-sheet"),
    OTHER("other"),
    ;

    companion object {
        fun fromWireValue(value: String): DocumentType? = entries.find { it.wireValue == value }
    }
}
