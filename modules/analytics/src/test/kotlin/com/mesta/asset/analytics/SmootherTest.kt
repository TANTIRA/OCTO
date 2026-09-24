package com.mesta.asset.analytics

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private val START = LocalDate.parse("2026-01-01")

private fun scalar(v: Double) = listOf(listOf(v))

private fun randomWalk(
    q: Double,
    r: Double,
) = LinearSystem(transition = scalar(1.0), processNoise = scalar(q), observation = scalar(1.0), observationNoise = scalar(r))

private fun series(vararg y: Double) = y.mapIndexed { i, v -> KalmanObservation(START.plusDays(i.toLong()), listOf(v)) }

class SmootherTest {
    @Test
    fun `smoothing a constant state carries the last filtered belief back`() {
        // Q = 0 and F = 1: the state never moves, so every smoothed estimate equals x̂_{T|T}.
        val system = randomWalk(q = 0.0, r = 1.0)
        val result = kalmanFilter(system, GaussianState(listOf(0.0), scalar(1.0)), series(1.0, 3.0, 2.0))
        val smoothed = rtsSmoother(system, result)

        val last = result.estimates.last().filtered
        smoothed.forEach {
            assertEquals(last.mean[0], it.mean[0], 1e-12)
            assertEquals(last.covariance[0][0], it.covariance[0][0], 1e-12)
        }
        assertEquals(1.5, last.mean[0], 1e-12) // precision-weighted: (0 + 1 + 3 + 2) / 4
    }

    @Test
    fun `smoothed variance never exceeds filtered variance`() {
        val system = randomWalk(q = 0.3, r = 1.0)
        val result = kalmanFilter(system, GaussianState(listOf(0.0), scalar(1.0)), series(1.0, 0.5, 2.0, 1.5, 1.0))
        val smoothed = rtsSmoother(system, result)

        result.estimates.zip(smoothed).forEach { (e, s) -> assert(s.covariance[0][0] <= e.filtered.covariance[0][0] + 1e-12) }
        assertEquals(result.estimates.last().filtered, smoothed.last())
    }
}
