package com.octo.analytics

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `Pacing.kt` (methodology §2.3): the identities, the zero-base nulls, and the disagreeing-input rejections. */
class PacingTest {
    @Test
    fun `unfunded is committed minus called and rejects disagreement`() {
        assertEquals(BigDecimal("30"), unfundedCommitment(BigDecimal("100"), BigDecimal("70")))
        assertEquals(BigDecimal.ZERO, unfundedCommitment(BigDecimal("100"), BigDecimal("100")))
        assertFailsWith<IllegalArgumentException> {
            unfundedCommitment(BigDecimal("100"), BigDecimal("120"))
        }
        assertFailsWith<IllegalArgumentException> {
            unfundedCommitment(BigDecimal("-1"), BigDecimal.ZERO)
        }
    }

    @Test
    fun `drawdown rate is called over committed with no rate on a zero commitment`() {
        assertEquals(0, drawdownRate(BigDecimal("200"), BigDecimal("150"))!!.compareTo(BigDecimal("0.75")))
        assertNull(drawdownRate(BigDecimal.ZERO, BigDecimal.ZERO))
        assertFailsWith<IllegalArgumentException> {
            drawdownRate(BigDecimal("100"), BigDecimal("150"))
        }
    }

    @Test
    fun `distribution rate is distributions over called with no rate before calls`() {
        val rate = distributionRate(BigDecimal("80"), BigDecimal("40"))!!
        assertTrue(rate.compareTo(BigDecimal("0.5")) == 0)
        assertNull(distributionRate(BigDecimal.ZERO, BigDecimal("10")))
        assertFailsWith<IllegalArgumentException> {
            distributionRate(BigDecimal("80"), BigDecimal("-1"))
        }
    }
}
