"""Bounded retry for outbound HTTP (backlog #326).

The judge and platform calls were single-shot: a transient 429 or 5xx failed
the whole run. This retries a request-sending callable on connection errors
and on the transient statuses below, with exponential backoff. Non-transient
4xx/5xx and the final attempt are returned to the caller to handle — the
retry never swallows a real error, it only re-tries the ones worth re-trying.

A non-idempotent request (one the server does not dedupe) is only re-sent when
it provably never reached the server — a connect-phase failure. A 5xx, a read
timeout or a connection dropped after send may mean the server already acted,
and a blind resend would duplicate the side effect.
"""

import time
from collections.abc import Callable

import httpx

# Retry only on statuses that are transient by contract: rate limiting and the
# upstream-unavailable family. A 4xx like 400/401/403/404/409 is the caller's
# to handle and is never retried.
RETRYABLE_STATUS = frozenset({429, 502, 503, 504})

# Transport failures raised before any request byte reached the server.
NOT_SENT_ERRORS = (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout)


def send_with_retry(
    send: Callable[[], httpx.Response],
    *,
    retries: int = 2,
    backoff_s: float = 0.5,
    idempotent: bool = True,
    sleep: Callable[[float], None] = time.sleep,
) -> httpx.Response:
    """Call [send] up to 1 + [retries] times, backing off on transport errors
    and RETRYABLE_STATUS responses. Returns the last response (or re-raises the
    last transport error) once retries are exhausted. With idempotent=False
    only NOT_SENT_ERRORS are retried; everything else surfaces on first try."""
    attempt = 0
    while True:
        try:
            response = send()
        except httpx.TransportError as e:
            if attempt >= retries or not (idempotent or isinstance(e, NOT_SENT_ERRORS)):
                raise
        else:
            if (
                not idempotent
                or response.status_code not in RETRYABLE_STATUS
                or attempt >= retries
            ):
                return response
        sleep(backoff_s * (2**attempt))
        attempt += 1
