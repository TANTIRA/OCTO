package com.octo.dealsourcing

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The screening evaluator: hard failures reject, missing fields review, satisfied mandates clear. */
class ScreeningTest {
    private val prospect =
        Prospect(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "acme-logistics",
            ProspectSource.REFERRAL,
            sector = "logistics",
            region = "SEA",
            description = null,
            registeredAt = Instant.parse("2026-09-01T00:00:00Z"),
        )

    @Test
    fun `a prospect inside the mandate clears`() {
        val outcome =
            ScreeningCriteria(
                sectors = setOf("logistics", "saas"),
                regions = setOf("SEA"),
                sources = setOf(ProspectSource.REFERRAL, ProspectSource.CRM),
            ).evaluate(prospect)
        assertEquals(ScreeningVerdict.CLEAR, outcome.verdict)
        assertTrue(outcome.reasons.isEmpty())
    }

    @Test
    fun `a violated constraint rejects with the offending value named`() {
        val outcome = ScreeningCriteria(sectors = setOf("saas")).evaluate(prospect)
        assertEquals(ScreeningVerdict.REJECT, outcome.verdict)
        assertEquals(listOf("sector 'logistics' is outside the mandate [saas]"), outcome.reasons)
    }

    @Test
    fun `a missing field reviews, and a hard failure still wins over it`() {
        val sectorless = prospect.copy(sector = null)
        assertEquals(
            ScreeningVerdict.REVIEW,
            ScreeningCriteria(sectors = setOf("logistics"), regions = setOf("SEA")).evaluate(sectorless).verdict,
        )
        assertEquals(
            ScreeningVerdict.REJECT,
            ScreeningCriteria(sectors = setOf("saas"), regions = setOf("EU")).evaluate(sectorless).verdict,
        ) // region fails outright even with sector missing
    }

    @Test
    fun `absent criteria fields are unconstrained and unknown document keys are ignored`() {
        assertEquals(ScreeningVerdict.CLEAR, ScreeningCriteria().evaluate(prospect).verdict)
        assertEquals(
            ScreeningVerdict.CLEAR,
            ScreeningCriteria
                .parse(mapOf("sectors" to listOf("logistics"), "sources" to listOf("referral")))
                .evaluate(prospect)
                .verdict,
        )
    }
}
