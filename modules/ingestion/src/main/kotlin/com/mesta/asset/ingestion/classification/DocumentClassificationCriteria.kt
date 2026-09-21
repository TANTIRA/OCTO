package com.mesta.asset.ingestion.classification

import com.mesta.asset.controlpanel.judgment.ChoiceQuestion

/**
 * The question, its option descriptions, and the review threshold in one place, so a reviewer can
 * see everything the model is being asked without reading the classifier.
 */
object DocumentClassificationCriteria {

    const val INSTRUCTIONS = "Which document type is this?"

    const val MIN_CONFIDENCE = 0.6

    val CRITERIA: Map<String, String?> = mapOf(
        DocumentType.PITCH_DECK.wireValue to "An investor or company pitch presentation.",
        DocumentType.DDQ.wireValue to "A due diligence questionnaire, its responses, or a DDQ library.",
        DocumentType.FINANCIALS.wireValue to "Financial statements, management accounts, or an audit report.",
        DocumentType.ICAP_REPORT.wireValue to "An investment committee or investment analysis paper.",
        DocumentType.MEMO.wireValue to "An internal memo, note, or investment summary.",
        DocumentType.LEGAL.wireValue to "Contracts, incorporation documents, or regulatory filings.",
        DocumentType.LP_REPORT.wireValue to "An investor report or capital account statement for limited partners.",
        DocumentType.TEAR_SHEET.wireValue to "A one-page summary or profile sheet.",
        DocumentType.OTHER.wireValue to "None of the above, or the content is unreadable.",
    )

    fun question(): ChoiceQuestion = ChoiceQuestion(INSTRUCTIONS, CRITERIA)
}
