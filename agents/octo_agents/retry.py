"""Bounded retry for outbound HTTP (backlog #326).

The judge and platform calls were single-shot: a transient 429 or 5xx failed
the whole run. This retries a request-sending callable on connection errors
and on the transient statuses below, with exponential backoff. Non-transient
4xx/5xx and the final attempt are returned to the caller to handle — the
retry never swallows a real error, it only re-tries the ones worth re-trying.
"""

import time
from collections.abc import Callable

import httpx

# Retry only on statuses that are transient by contract: rate limiting and the
# upstream-unavailable family. A 4xx like 400/401/403/404/409 is the caller's
# to handle and is never retried.
RETRYABLE_STATUS = frozenset({429, 502, 503, 504})


def send_with_retry(
    send: Callable[[], httpx.Response],
    *,
    retries: int = 2,
    backoff_s: float = 0.5,
    sleep: Callable[[float], None] = time.sleep,
) -> httpx.Response:
    """Call [send] up to 1 + [retries] times, backing off on transport errors
    and RETRYABLE_STATUS responses. Returns the last response (or re-raises the
    last transport error) once retries are exhausted."""
    attempt = 0
    while True:
        try:
            response = send()
        except httpx.TransportError:
            if attempt >= retries:
                raise
        else:
            if response.status_code not in RETRYABLE_STATUS or attempt >= retries:
                return response
        sleep(backoff_s * (2**attempt))
        attempt += 1
