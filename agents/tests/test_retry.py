"""backlog #326 — transient 429/5xx and connection errors are retried with
backoff; non-transient responses and exhausted retries surface to the caller.
Sleep is injected so the tests never actually wait."""

import httpx
import pytest

from octo_agents.retry import RETRYABLE_STATUS, send_with_retry


def _noop_sleep(_seconds: float) -> None:
    return None


def _sequence(*responses: object):
    """A send() that yields the given responses/exceptions in order."""
    it = iter(responses)

    def send() -> httpx.Response:
        item = next(it)
        if isinstance(item, Exception):
            raise item
        return item  # type: ignore[return-value]

    return send


def _resp(status: int) -> httpx.Response:
    return httpx.Response(status, request=httpx.Request("POST", "https://x"))


def test_retries_on_transient_status_then_succeeds() -> None:
    send = _sequence(_resp(503), _resp(200))
    out = send_with_retry(send, retries=2, backoff_s=0, sleep=_noop_sleep)
    assert out.status_code == 200


def test_retries_on_transport_error_then_succeeds() -> None:
    send = _sequence(httpx.ConnectError("boom"), _resp(200))
    out = send_with_retry(send, retries=2, backoff_s=0, sleep=_noop_sleep)
    assert out.status_code == 200


def test_returns_last_transient_after_exhausting_retries() -> None:
    send = _sequence(_resp(503), _resp(503), _resp(503))
    out = send_with_retry(send, retries=2, backoff_s=0, sleep=_noop_sleep)
    assert out.status_code == 503


def test_reraises_transport_error_after_exhausting_retries() -> None:
    send = _sequence(
        httpx.ConnectError("a"), httpx.ConnectError("b"), httpx.ConnectError("c")
    )
    with pytest.raises(httpx.ConnectError):
        send_with_retry(send, retries=2, backoff_s=0, sleep=_noop_sleep)


def test_non_transient_status_is_not_retried() -> None:
    calls = {"n": 0}

    def send() -> httpx.Response:
        calls["n"] += 1
        return _resp(404)

    out = send_with_retry(send, retries=2, backoff_s=0, sleep=_noop_sleep)
    assert out.status_code == 404
    assert calls["n"] == 1  # 404 is the caller's to handle, never retried


def test_non_idempotent_retries_only_unsent_requests() -> None:
    # Connect failed: nothing reached the server, a resend cannot duplicate.
    send = _sequence(httpx.ConnectError("refused"), _resp(200))
    out = send_with_retry(send, idempotent=False, backoff_s=0, sleep=_noop_sleep)
    assert out.status_code == 200

    # 503 or a read timeout after send: the server may have acted — surface it.
    send = _sequence(_resp(503), _resp(200))
    out = send_with_retry(send, idempotent=False, backoff_s=0, sleep=_noop_sleep)
    assert out.status_code == 503

    send = _sequence(httpx.ReadTimeout("slow"), _resp(200))
    with pytest.raises(httpx.ReadTimeout):
        send_with_retry(send, idempotent=False, backoff_s=0, sleep=_noop_sleep)


def test_retryable_set_is_transient_only() -> None:
    assert RETRYABLE_STATUS == {429, 502, 503, 504}
