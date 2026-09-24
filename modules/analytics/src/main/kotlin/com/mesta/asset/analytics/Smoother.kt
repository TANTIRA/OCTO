package com.mesta.asset.analytics

/**
 * Rauch–Tung–Striebel backward pass over a [kalmanFilter] result (§9.3). The smoothed states use future
 * observations: they are for historical analysis only and never a live point-in-time signal.
 */
fun rtsSmoother(
    system: LinearSystem,
    result: KalmanResult,
): List<GaussianState> {
    val f = system.transition.toArray()
    val estimates = result.estimates
    if (estimates.isEmpty()) return emptyList()
    val smoothed = arrayOfNulls<GaussianState>(estimates.size)
    smoothed[estimates.lastIndex] = estimates.last().filtered
    for (t in estimates.lastIndex - 1 downTo 0) {
        val filtered = estimates[t].filtered
        val nextPredicted = estimates[t + 1].predicted
        val next = smoothed[t + 1]!!
        val pFiltered = filtered.covariance.toArray()
        val pPredicted = nextPredicted.covariance.toArray()
        // J_t = P_{t|t} Fᵀ P_{t+1|t}⁻¹.
        val gain =
            pFiltered.times(f.transpose()).times(
                cholesky(pPredicted) {
                    "predicted covariance at step ${t + 1} is not positive definite"
                }.inverse(),
            )
        val meanDelta = next.mean.toDoubleArray().minus(nextPredicted.mean.toDoubleArray())
        val covDelta = next.covariance.toArray().minus(pPredicted)
        smoothed[t] =
            GaussianState(
                filtered.mean
                    .toDoubleArray()
                    .plus(gain.times(meanDelta))
                    .toList(),
                pFiltered.plus(gain.times(covDelta).times(gain.transpose())).toLists(),
            )
    }
    return smoothed.map { it!! }
}
