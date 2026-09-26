package com.mesta.asset.ingestion.http

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {
    private val policy = RetryPolicy()

    @Test
    fun `429 and 5xx are retried, 4xx client errors are not`() {
        assertTrue(policy.shouldRetry(429, 1))
        assertTrue(policy.shouldRetry(500, 1))
        assertTrue(policy.shouldRetry(503, 2))
        for (status in listOf(400, 401, 403, 404, 422)) {
            assertFalse(policy.shouldRetry(status, 1), "status $status must never be retried")
        }
    }

    @Test
    fun `no retry past maxAttempts`() {
        assertTrue(policy.shouldRetry(500, 1))
        assertTrue(policy.shouldRetry(500, 2))
        assertFalse(policy.shouldRetry(500, 3))
    }

    @Test
    fun `backoff doubles from baseDelay and is capped`() {
        assertEquals(Duration.ofSeconds(2), policy.delayFor(1, null))
        assertEquals(Duration.ofSeconds(4), policy.delayFor(2, null))
        val capped = RetryPolicy(maxAttempts = 10, maxDelay = Duration.ofSeconds(5))
        assertEquals(Duration.ofSeconds(5), capped.delayFor(6, null))
    }

    @Test
    fun `server Retry-After wins over computed backoff`() {
        assertEquals(Duration.ofSeconds(17), policy.delayFor(1, Duration.ofSeconds(17)))
        val capped = RetryPolicy(maxDelay = Duration.ofSeconds(5))
        assertEquals(Duration.ofSeconds(5), capped.delayFor(1, Duration.ofSeconds(60)))
    }
}
