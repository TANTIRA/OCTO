"""End-to-end run deadline (#486, #554).

The platform gives up on a workflow call after 120 s (AgentsClient.kt). A run
holds one monotonic deadline, set per request in server.py and kept in a
contextvar. Every judge and platform attempt checks it and caps its HTTP
timeout to the time left (retry.py). Every drafter call checks it at each
model or tool step (`invoke_within_deadline`). The drafter's HTTP client is
also capped to the time left on every attempt and closed when the run stops
waiting, so an abandoned model call cannot keep retrying and billing (#554).
`finish_failed` still records the failure in a short grace window. The budget
plus the grace window stays under 120 s, so the run is recorded `failed`
before the platform gives up.
"""

import contextvars
import logging
import threading
import time
import weakref
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Any

import httpx
from langchain_core.callbacks import BaseCallbackHandler

# Bookkeeping window after expiry: enough for the one `finish_run(failed)`.
BOOKKEEPING_GRACE_S = 10.0

_log = logging.getLogger("octo_agents.deadline")


class DeadlineExceeded(TimeoutError):
    """The run's budget ran out; recorded via finish_failed, answered 504."""

    def __init__(self, stage: str) -> None:
        super().__init__(f"run deadline exceeded before {stage}")
        self.stage = stage


class Deadline:
    def __init__(self, budget_s: float, *, clock: Callable[[], float] = time.monotonic) -> None:
        self.clock = clock
        self._expires_at = clock() + budget_s
        self._clients: list[Any] = []
        self._clients_lock = threading.Lock()

    def remaining(self) -> float:
        return max(0.0, self._expires_at - self.clock())

    def check(self, stage: str) -> None:
        if self.remaining() <= 0:
            raise DeadlineExceeded(stage)

    def track(self, client: Any) -> None:
        """Remember a drafter HTTP client so it can be closed at the deadline."""
        with self._clients_lock:
            if client not in self._clients:
                self._clients.append(client)

    def cancel_inflight(self) -> None:
        """Close tracked drafter clients so an abandoned call cannot keep billing."""
        with self._clients_lock:
            clients = list(self._clients)
        for client in clients:
            close = getattr(client, "close", None)
            if close is None:
                continue
            try:
                close()
            except Exception:  # noqa: BLE001 - one client must not block the rest
                _log.warning("failed to close drafter client at the deadline", exc_info=True)


_current: contextvars.ContextVar[Deadline | None] = contextvars.ContextVar(
    "octo_run_deadline", default=None
)


@contextmanager
def _active(deadline: Deadline | None) -> Iterator[None]:
    token = _current.set(deadline)
    try:
        yield
    finally:
        _current.reset(token)


@contextmanager
def run_deadline(
    budget_s: float, *, clock: Callable[[], float] = time.monotonic
) -> Iterator[Deadline]:
    """Holds a fresh deadline of `budget_s` for everything run inside the block."""
    deadline = Deadline(budget_s, clock=clock)
    with _active(deadline):
        yield deadline


@contextmanager
def bookkeeping_grace() -> Iterator[None]:
    """A short fresh window for failure bookkeeping; no-op without a deadline."""
    current = _current.get()
    grace = None if current is None else Deadline(BOOKKEEPING_GRACE_S, clock=current.clock)
    with _active(grace):
        yield


def check(stage: str) -> None:
    current = _current.get()
    if current is not None:
        current.check(stage)


def expired() -> bool:
    current = _current.get()
    return current is not None and current.remaining() <= 0


def track_model_client(client: Any) -> None:
    """Register `client` with the active run, if a deadline is set."""
    current = _current.get()
    if current is not None:
        current.track(client)


def _cap_phase(phase: float | None, left: float) -> float:
    return left if phase is None else min(float(phase), left)


def _cap_request_timeout(request: httpx.Request, left: float) -> None:
    """Each phase of this attempt's timeout, capped to the time left."""
    existing = request.extensions.get("timeout")
    if existing is None or existing is httpx.USE_CLIENT_DEFAULT:
        request.extensions["timeout"] = httpx.Timeout(left)
        return
    if isinstance(existing, (int, float)):
        request.extensions["timeout"] = httpx.Timeout(min(float(existing), left))
        return
    request.extensions["timeout"] = httpx.Timeout(
        connect=_cap_phase(existing.connect, left),
        read=_cap_phase(existing.read, left),
        write=_cap_phase(existing.write, left),
        pool=_cap_phase(existing.pool, left),
    )


class _DeadlineTransport(httpx.BaseTransport):
    """Stops a drafter HTTP attempt from outliving the run (#554).

    The OpenRouter SDK retries a timed-out chat call inside its own
    `max_elapsed_time` window (minutes, with `max_retries=2`). Single-step
    drafters never reach another model step, so that window used to run — and
    bill — after the workflow had already given up. This transport caps the
    attempt to the time left and refuses a send once the deadline has passed,
    which ends the SDK retry loop without another provider request.
    """

    def __init__(self, inner: httpx.BaseTransport, owner: httpx.Client | None) -> None:
        self._inner = inner
        self._owner = owner

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        current = _current.get()
        if current is None:
            return self._inner.handle_request(request)
        if self._owner is not None:
            current.track(self._owner)
        left = current.remaining()
        if left <= 0:
            raise DeadlineExceeded("drafter model call")
        _cap_request_timeout(request, left)
        return self._inner.handle_request(request)

    def close(self) -> None:
        self._inner.close()


_bound_clients: weakref.WeakSet[httpx.Client] = weakref.WeakSet()


def bind_drafter_http_client(model: Any) -> None:
    """Wrap the drafter SDK's HTTP client so every attempt honours the deadline."""
    sdk = getattr(model, "client", None)
    config = getattr(sdk, "sdk_configuration", None)
    http = getattr(config, "client", None) if config is not None else None
    if not isinstance(http, httpx.Client):
        return
    if http not in _bound_clients:
        http._transport = _DeadlineTransport(http._transport, http)
        _bound_clients.add(http)
    track_model_client(http)


def drafter_request_overrides(request_timeout_ms: int | None) -> dict[str, Any]:
    """Per-call SDK timeout and retry budget, both capped to the time left.

    Empty when no deadline is active, so a model built for evals keeps its
    configured timeout. `timeout_ms` is what ChatOpenRouter forwards to
    `chat.send`; the retry config replaces the SDK default of
    `max_retries * 150s`, which otherwise keeps billing after the run fails.
    """
    current = _current.get()
    if current is None:
        return {}
    if current.remaining() <= 0:
        raise DeadlineExceeded("drafter model call")
    left_ms = int(current.remaining() * 1000)
    if left_ms <= 0:
        raise DeadlineExceeded("drafter model call")
    if request_timeout_ms is not None:
        left_ms = min(int(request_timeout_ms), left_ms)
    from openrouter.utils import BackoffStrategy, RetryConfig

    return {
        "timeout_ms": left_ms,
        "retries": RetryConfig(
            strategy="backoff",
            backoff=BackoffStrategy(
                initial_interval=500,
                max_interval=min(60_000, left_ms),
                exponent=1.5,
                max_elapsed_time=left_ms,
            ),
            retry_connection_errors=True,
        ),
    }


def http_timeout(client: httpx.Client) -> Any:
    """The client's timeout, each phase capped to the time left in the run."""
    current = _current.get()
    if current is None:
        return httpx.USE_CLIENT_DEFAULT
    left = current.remaining()
    base = client.timeout

    def cap(phase: float | None) -> float:
        return left if phase is None else min(phase, left)

    return httpx.Timeout(
        connect=cap(base.connect), read=cap(base.read), write=cap(base.write), pool=cap(base.pool)
    )


class _DeadlineCallback(BaseCallbackHandler):
    """Aborts a drafter at its next model/tool step; raise_error propagates it."""

    raise_error = True

    def __init__(self, deadline: Deadline) -> None:
        self._deadline = deadline

    def on_chat_model_start(self, *args: Any, **kwargs: Any) -> None:
        self._deadline.check("drafter model call")

    def on_tool_start(self, *args: Any, **kwargs: Any) -> None:
        self._deadline.check("drafter tool call")


def invoke_within_deadline(runnable: Any, payload: Any) -> Any:
    """Runs `runnable.invoke(payload)` on a daemon thread in a copy of this
    context, and stops waiting at the deadline. Stopping also closes the
    drafter HTTP client, so the in-flight provider call and its retries cannot
    keep running after the run has failed. Drafters have no write path."""
    current = _current.get()
    if current is None:
        return runnable.invoke(payload)
    current.check("drafter call")
    config = {"callbacks": [_DeadlineCallback(current)]}
    outcome: dict[str, Any] = {}
    done = threading.Event()
    context = contextvars.copy_context()

    def target() -> None:
        try:
            outcome["value"] = context.run(runnable.invoke, payload, config)
        except BaseException as e:  # noqa: BLE001 - re-raised on the caller's thread
            outcome["error"] = e
        finally:
            done.set()

    threading.Thread(target=target, name="octo-drafter", daemon=True).start()
    if not done.wait(timeout=current.remaining()):
        current.cancel_inflight()
        raise DeadlineExceeded("drafter call completed")
    if "error" in outcome:
        raise outcome["error"]
    return outcome["value"]
