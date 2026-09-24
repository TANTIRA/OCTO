package com.mesta.asset.analytics

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val START = LocalDate.parse("2026-01-01")

private fun scalar(v: Double) = listOf(listOf(v))

/** Random walk x_t = x_{t−1} + w, observed directly: y_t = x_t + v. */
private fun randomWalk(
    q: Double,
    r: Double,
) = LinearSystem(transition = scalar(1.0), processNoise = scalar(q), observation = scalar(1.0), observationNoise = scalar(r))

private fun series(vararg y: Double?) = y.mapIndexed { i, v -> KalmanObservation(START.plusDays(i.toLong()), v?.let { listOf(it) }) }

class KalmanTest {
    @Test
    fun `one scalar step follows methodology 9_3`() {
        // P⁻ = 1 + 0.5 = 1.5, K = 1.5 / (1.5 + 1) = 0.6, x = 0 + 0.6 × (2 − 0) = 1.2, P = (1 − 0.6) × 1.5 = 0.6.
        val result = kalmanFilter(randomWalk(q = 0.5, r = 1.0), GaussianState(listOf(0.0), scalar(1.0)), series(2.0))
        val step = result.estimates.single()

        assertEquals(1.5, step.predicted.covariance[0][0], 1e-12)
        assertEquals(listOf(2.0), step.innovation)
        assertEquals(2.5, step.innovationCovariance!![0][0], 1e-12)
        assertEquals(1.2, step.filtered.mean[0], 1e-12)
        assertEquals(0.6, step.filtered.covariance[0][0], 1e-12)
        assertEquals(2.0 / sqrt(2.5), step.standardizedInnovation!![0], 1e-12)
        val expectedLogLikelihood = -0.5 * (ln(2 * PI) + ln(2.5) + 4.0 / 2.5)
        assertEquals(expectedLogLikelihood, step.logLikelihood!!, 1e-12)
        assertEquals(expectedLogLikelihood, result.logLikelihood, 1e-12)
        assertEquals(KALMAN_METHODOLOGY, result.methodology)
    }

    @Test
    fun `predicted variance converges to the Riccati fixed point`() {
        // P⁻ solves P = P + q − P² / (P + r): P⁻ = (q + √(q² + 4qr)) / 2.
        val (q, r) = 0.25 to 1.0
        val observations = series(*Array(200) { 0.0 })
        val result = kalmanFilter(randomWalk(q, r), GaussianState(listOf(0.0), scalar(10.0)), observations)

        assertEquals(
            (q + sqrt(q * q + 4 * q * r)) / 2,
            result.estimates
                .last()
                .predicted.covariance[0][0],
            1e-9,
        )
    }

    @Test
    fun `a missing observation predicts through and skips the update`() {
        val result = kalmanFilter(randomWalk(q = 0.5, r = 1.0), GaussianState(listOf(0.0), scalar(1.0)), series(2.0, null, 2.0))
        val gap = result.estimates[1]

        assertNull(gap.innovation)
        assertNull(gap.logLikelihood)
        assertEquals(gap.predicted, gap.filtered)
        assertEquals(1.2, gap.filtered.mean[0], 1e-12)
        assertEquals(1.1, gap.filtered.covariance[0][0], 1e-12) // 0.6 + 0.5
        assertNotNull(result.estimates[2].innovation)
    }

    @Test
    fun `dynamic regression recovers a constant hedge ratio (9_4)`() {
        // y = α + βx with α = 1, β = 2; state (α, β) is a random walk observed through H_t = [1, x_t].
        // The methodology's time-varying H is expressed by putting x_t into the control-free system per step.
        val xs = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        var state = GaussianState(listOf(0.0, 0.0), listOf(listOf(100.0, 0.0), listOf(0.0, 100.0)))
        var lastEstimate: KalmanEstimate? = null
        xs.forEachIndexed { i, x ->
            val system =
                LinearSystem(
                    transition = listOf(listOf(1.0, 0.0), listOf(0.0, 1.0)),
                    processNoise = listOf(listOf(0.0, 0.0), listOf(0.0, 0.0)),
                    observation = listOf(listOf(1.0, x)),
                    observationNoise = scalar(1e-6),
                )
            val result = kalmanFilter(system, state, listOf(KalmanObservation(START.plusDays(i.toLong()), listOf(1.0 + 2.0 * x))))
            lastEstimate = result.estimates.single()
            state = lastEstimate!!.filtered
        }

        assertEquals(1.0, state.mean[0], 1e-4)
        assertEquals(2.0, state.mean[1], 1e-4)
        assertEquals(0.0, lastEstimate!!.standardizedInnovation!![0], 1e-3)
    }

    @Test
    fun `control input enters the prediction`() {
        val system = LinearSystem(scalar(1.0), scalar(0.0), scalar(1.0), scalar(1.0), control = scalar(2.0))
        val result =
            kalmanFilter(system, GaussianState(listOf(1.0), scalar(0.0)), listOf(KalmanObservation(START, listOf(9.0), listOf(3.0))))

        assertEquals(
            7.0,
            result.estimates
                .single()
                .predicted.mean[0],
            1e-12,
        ) // 1 + 2 × 3
        assertEquals(
            7.0,
            result.estimates
                .single()
                .filtered.mean[0],
            1e-12,
        ) // P⁻ = 0, so the measurement is ignored
    }

    @Test
    fun `invalid inputs are rejected`() {
        val system = randomWalk(0.5, 1.0)
        val initial = GaussianState(listOf(0.0), scalar(1.0))
        assertFailsWith<IllegalArgumentException> { GaussianState(listOf(0.0), listOf(listOf(1.0, 0.0))) }
        assertFailsWith<IllegalArgumentException> { GaussianState(listOf(Double.NaN), scalar(1.0)) }
        assertFailsWith<IllegalArgumentException> { LinearSystem(scalar(1.0), scalar(1.0), listOf(listOf(1.0, 1.0)), scalar(1.0)) }
        assertFailsWith<IllegalArgumentException> {
            kalmanFilter(system, GaussianState(listOf(0.0, 0.0), listOf(listOf(1.0, 0.0), listOf(0.0, 1.0))), series(1.0))
        }
        assertFailsWith<IllegalArgumentException> { kalmanFilter(system, initial, series(1.0, 2.0).reversed()) }
        assertFailsWith<IllegalArgumentException> { kalmanFilter(system, initial, listOf(KalmanObservation(START, listOf(1.0, 2.0)))) }
        assertFailsWith<IllegalArgumentException> {
            kalmanFilter(
                system,
                initial,
                listOf(KalmanObservation(START, listOf(1.0), listOf(1.0))),
            )
        }
        assertFailsWith<IllegalArgumentException> { kalmanFilter(system, initial, listOf(KalmanObservation(START, listOf(Double.NaN)))) }
        assertFailsWith<IllegalArgumentException> {
            kalmanFilter(
                randomWalk(0.0, 0.0),
                GaussianState(listOf(0.0), scalar(0.0)),
                series(1.0),
            )
        }
    }
}
