package com.mesta.asset.analytics

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun m(vararg rows: DoubleArray) = arrayOf(*rows)

private fun v(vararg values: Double) = values

/** A NumPy reference vector, space-separated, so each stays on one line. */
private fun numpy(values: String) = values.split(' ').map(String::toDouble)

private fun assertAll(
    expected: List<Double>,
    actual: List<Double>,
    tolerance: Double,
) {
    assertEquals(expected.size, actual.size)
    expected.indices.forEach { assertEquals(expected[it], actual[it], tolerance, "index $it") }
}

// Local level: x_t = x_{t−1} + w (q = 0.01), y_t = x_t + v (r = 0.1), x_0 = 0, P_0 = 1.
private val LEVEL = KalmanModel(m(v(1.0)), m(v(0.01)), m(v(0.1)), v(0.0), m(v(1.0)))
private val LEVEL_Y = listOf(0.5, 0.7, 0.4, 0.9, 1.1)

// Constant velocity, dt = 1: F = [[1, 1], [0, 1]], H = [1, 0], Q = 0.001·I, R = 0.25, x_0 = 0, P_0 = 10·I.
private val VELOCITY =
    KalmanModel(m(v(1.0, 1.0), v(0.0, 1.0)), m(v(0.001, 0.0), v(0.0, 0.001)), m(v(0.25)), v(0.0, 0.0), m(v(10.0, 0.0), v(0.0, 10.0)))
private val VELOCITY_Y = listOf(1.0, 2.1, 2.9, 4.2, 5.0, 5.9)

private fun level() = kalmanFilter(LEVEL, LEVEL_Y.map { v(it) }) { m(v(1.0)) }

private fun velocity() = kalmanFilter(VELOCITY, VELOCITY_Y.map { v(it) }) { m(v(1.0, 0.0)) }

class KalmanTest {
    @Test
    fun `the local-level filter matches numpy with the joseph update`() {
        val filtered = level()

        // NumPy reference, same model and data.
        assertAll(
            numpy("0.45495495495495492 0.5780815777678171 0.5111297586081508 0.63653090656867628 0.77418133061976757"),
            filtered.states.map { it[0] },
            1e-12,
        )
        assertAll(
            numpy("0.090990990990990991 0.050246526221425371 0.037596151156611003 0.0322475557686513 0.029700022288862322"),
            filtered.covariances.map { it[0][0] },
            1e-12,
        )
        assertEquals(STATE_SPACE_METHODOLOGY, filtered.methodology)
    }

    @Test
    fun `the gain converges to the closed-form steady state`() {
        val filtered = kalmanFilter(LEVEL, List(200) { v(1.0) }) { m(v(1.0)) }
        val predicted = filtered.predictedCovariances.last()[0][0]

        // Steady-state predicted variance (q + √(q² + 4qr)) / 2 and gain P / (P + r).
        assertEquals(0.037015621187164241, predicted, 1e-12)
        assertEquals(0.2701562118716424, predicted / (predicted + 0.1), 1e-12)
    }

    @Test
    fun `the rts smoother matches numpy and ends on the last filtered state`() {
        val filtered = level()
        val smoothed = rtsSmooth(filtered)

        assertAll(
            numpy("0.65237654827990066 0.67407337685323621 0.6931775431118955 0.74159946368174434 0.77418133061976757"),
            smoothed.states.map { it[0] },
            1e-12,
        )
        assertAll(
            numpy("0.029025760888868643 0.024657250455003218 0.023458208249916634 0.02493702696952341 0.029700022288862322"),
            smoothed.covariances.map { it[0][0] },
            1e-12,
        )
        assertEquals(filtered.states.last()[0], smoothed.states.last()[0])
    }

    @Test
    fun `a two-dimensional constant-velocity model matches numpy and keeps covariances symmetric`() {
        val filtered = velocity()
        val smoothed = rtsSmooth(filtered)

        assertAll(
            numpy("0.98765493062071008 2.0733744430125038 2.9400865624899017 4.1020908375561493 5.0550404763442618 5.9771719749036185"),
            filtered.states.map { it[0] },
            1e-10,
        )
        assertAll(
            numpy("0.49380277517159643 1.0461683735937053 0.94103125128392595 1.0346929807675258 1.0074771513146146 0.9839137916437376"),
            filtered.states.map { it[1] },
            1e-10,
        )
        assertAll(
            numpy("1.054143627697026 2.0394262615901262 3.0243346889454306 4.0096273470003956 4.9935668711594952 5.9771719749036185"),
            smoothed.states.map { it[0] },
            1e-10,
        )
        assertAll(
            numpy("0.13128676252645952 0.03624739052974664 0.016523890240470088"),
            filtered.covariances.last().let { listOf(it[0][0], it[0][1], it[1][1]) },
            1e-10,
        )
        for (p in filtered.covariances + smoothed.covariances) assertEquals(p[0][1], p[1][0], 0.0)
    }

    @Test
    fun `dynamic regression tracks a drifting beta and standardizes residuals to unit variance`() {
        // Simulated from the model itself: α and β random walks, Gaussian measurement noise, fixed seed.
        val random = Random(7)
        val q = m(v(1e-6, 0.0), v(0.0, 1e-4))
        val r = 1e-4
        var alpha = 0.2
        var beta = 1.0
        val trueBetas = mutableListOf<Double>()
        val xs = List(2000) { 1.0 + random.nextGaussian() }
        val ys =
            xs.map { x ->
                alpha += sqrt(q[0][0]) * random.nextGaussian()
                beta += sqrt(q[1][1]) * random.nextGaussian()
                trueBetas += beta
                alpha + beta * x + sqrt(r) * random.nextGaussian()
            }

        val fit = dynamicRegression(xs, ys, q, r, v(0.0, 0.0), m(v(1.0, 0.0), v(0.0, 1.0)))

        val trackingError =
            fit.betas.indices
                .drop(100)
                .map { abs(fit.betas[it] - trueBetas[it]) }
                .average()
        assertTrue(trackingError < 0.01, "mean |β̂ − β| after burn-in was $trackingError")
        val z = fit.standardizedResiduals.drop(100)
        val variance = z.sumOf { it * it } / z.size
        assertTrue(variance in 0.9..1.1, "Var(Z) was $variance")
        assertEquals(fit.residuals.size, fit.standardizedResiduals.size)
    }

    @Test
    fun `the log-likelihood and standardized innovation follow 9_3 and 9_6`() {
        // Step 0 of the local level: P⁻ = 1.01, S = 1.11, ỹ = 0.5.
        val filtered = level()

        assertEquals(-0.5 * (ln(2 * PI) + ln(1.11) + 0.25 / 1.11), filtered.logLikelihoods[0]!!, 1e-12)
        assertEquals(0.5 / sqrt(1.11), filtered.standardizedInnovations[0]!![0], 1e-12)
        assertEquals(filtered.logLikelihoods.sumOf { it!! }, filtered.logLikelihood, 1e-12)
    }

    @Test
    fun `a missing observation predicts through and skips the update`() {
        val filtered = kalmanFilter(LEVEL, listOf(v(0.5), null, v(0.7))) { m(v(1.0)) }
        val full = level()

        assertEquals(full.states[0][0], filtered.states[1][0], 1e-12)
        assertEquals(full.covariances[0][0][0] + 0.01, filtered.covariances[1][0][0], 1e-12)
        assertEquals(filtered.predictedStates[1][0], filtered.states[1][0], 1e-12)
        assertNull(filtered.innovations[1])
        assertNull(filtered.innovationCovariances[1])
        assertNull(filtered.standardizedInnovations[1])
        assertNull(filtered.logLikelihoods[1])
        assertEquals(filtered.logLikelihoods[0]!! + filtered.logLikelihoods[2]!!, filtered.logLikelihood, 1e-12)
        assertEquals(3, filtered.states.size)
    }

    @Test
    fun `a control input enters the prediction`() {
        // x_0 = 1 with P_0 = 0 and Q = 0: the prediction 1 + 2 × 3 = 7 is certain, so the measurement is ignored.
        val model = KalmanModel(m(v(1.0)), m(v(0.0)), m(v(1.0)), v(1.0), m(v(0.0)), control = m(v(2.0)))
        val filtered = kalmanFilter(model, listOf(v(9.0)), controls = listOf(v(3.0))) { m(v(1.0)) }

        assertEquals(7.0, filtered.predictedStates[0][0], 1e-12)
        assertEquals(7.0, filtered.states[0][0], 1e-12)
        assertFailsWith<IllegalArgumentException> { kalmanFilter(model, listOf(v(9.0))) { m(v(1.0)) } }
        assertFailsWith<IllegalArgumentException> { kalmanFilter(LEVEL, listOf(v(9.0)), controls = listOf(v(3.0))) { m(v(1.0)) } }
        assertFailsWith<IllegalArgumentException> { kalmanFilter(model, listOf(v(9.0)), controls = listOf(v(3.0), v(1.0))) { m(v(1.0)) } }
    }

    @Test
    fun `invalid models and inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { KalmanModel(m(v(1.0, 0.0)), m(v(0.01)), m(v(0.1)), v(0.0), m(v(1.0))) }
        assertFailsWith<IllegalArgumentException> {
            KalmanModel(m(v(1.0, 0.0), v(0.0, 1.0)), m(v(1.0, 0.5), v(0.4, 1.0)), m(v(0.1)), v(0.0, 0.0), m(v(1.0, 0.0), v(0.0, 1.0)))
        }
        assertFailsWith<IllegalArgumentException> { KalmanModel(m(v(1.0)), m(v(0.01)), m(v(0.1)), v(Double.NaN), m(v(1.0))) }
        assertFailsWith<IllegalArgumentException> { kalmanFilter(LEVEL, emptyList()) { m(v(1.0)) } }
        assertFailsWith<IllegalArgumentException> { kalmanFilter(LEVEL, listOf(v(Double.POSITIVE_INFINITY))) { m(v(1.0)) } }
        assertFailsWith<IllegalArgumentException> { kalmanFilter(LEVEL, listOf(v(1.0))) { m(v(1.0, 0.0)) } }
        // A negative measurement variance makes S negative: not positive definite.
        assertFailsWith<IllegalArgumentException> {
            kalmanFilter(KalmanModel(m(v(1.0)), m(v(0.0)), m(v(-2.0)), v(0.0), m(v(1.0))), listOf(v(1.0))) { m(v(1.0)) }
        }
        assertFailsWith<IllegalArgumentException> {
            dynamicRegression(listOf(1.0), listOf(1.0, 2.0), m(v(1e-6, 0.0), v(0.0, 1e-4)), 1e-4, v(0.0, 0.0), m(v(1.0, 0.0), v(0.0, 1.0)))
        }
    }
}
