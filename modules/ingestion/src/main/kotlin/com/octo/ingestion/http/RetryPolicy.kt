package com.mesta.asset.ingestion.http

import java.time.Duration

/**
 * Retry rules for vendor HTTP calls (helius-fit-assessment.md §operational risks): honor
 * `Retry-After` on 429, exponential backoff on 429/5xx, never retry client errors — a 4xx is a
 * bug or an auth failure and retrying it only burns quota.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelay: Duration = Duration.ofSeconds(2),
    val maxDelay: Duration = Duration.ofSeconds(30),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /** True when [status] after [attempt] (1-based) is worth another try. */
    fun shouldRetry(
        status: Int,
        attempt: Int,
    ): Boolean = attempt < maxAttempts && (status == 429 || status in 500..599)

    /**
     * Delay before attempt [attempt] + 1. `Retry-After` wins when the server sent one; otherwise
     * 2s/4s/8s… capped at [maxDelay].
     */
    fun delayFor(
        attempt: Int,
        retryAfter: Duration?,
    ): Duration {
        if (retryAfter != null) return retryAfter.coerceAtMost(maxDelay)
        val backoff = baseDelay.multipliedBy(1L shl (attempt - 1))
        return backoff.coerceAtMost(maxDelay)
    }
}
