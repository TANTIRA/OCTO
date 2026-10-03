"""End-to-end run deadline (#486).

The platform gives up on a workflow call after 120 s (AgentsClient.kt). A run
holds one monotonic deadline, set per request in server.py and kept in a
contextvar. Every judge and platform attempt checks it and caps its HTTP
timeout to the time left (retry.py). Every drafter call checks it at each
model or tool step (`invoke_within_deadline`). `finish_failed` still records
the failure in a short grace window. The budget plus the grace window stays
under 120 s, so the run is recorded `failed` before the platform gives up.
"""

import contextlib
import contextvars
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Any

import httpx
from langchain_core.callbacks import BaseCallbackHandler

# Bookkeeping window after expiry: enough for the one `finish_run(failed)`.
BOOKKEEPING_GRACE_S = 10.0


class DeadlineExceeded(TimeoutError):
    """The run's budget ran out; recorded via finish_failed, answered 504."""

    def __init__(self, stage: str) -> None:
        super().__init__(f"run deadline exceeded before {stage}")
        self.stage = stage


class Deadline:
    def __init__(
        self,
        budget_s: float,
        *,
        clock: Callable[[], float] = time.monotonic,
        started_at: float | None = None,
    ) -> None:
        self.clock = clock
        # The platform's own timeout starts when the request arrives, so the
        # budget must too — including any time spent queued for a worker (#555).
        self._expires_at = (started_at if started_at is not None else clock()) + budget_s

    def remaining(self) -> float:
        return max(0.0, self._expires_at - self.clock())

    def check(self, stage: str) -> None:
        if self.remaining() <= 0:
            raise DeadlineExceeded(stage)


_current: contextvars.ContextVar[Deadline | None] = contextvars.ContextVar(
    "octo_run_deadline", default=None
)

# Stamped by the ASGI layer the moment the request is received — before any
# threadpool queue — so `run_deadline` can measure from arrival, not pickup.
_arrived_at: contextvars.ContextVar[float | None] = contextvars.ContextVar(
    "octo_request_arrived_at", default=None
)


def request_arrived(now: float) -> contextvars.Token[float | None]:
    """Stamps the receipt instant for the in-flight request; reset with the token."""
    return _arrived_at.set(now)


def clear_arrival(token: contextvars.Token[float | None]) -> None:
    _arrived_at.reset(token)


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
    """Holds a fresh deadline of `budget_s` for everything run inside the block.
    When the ASGI layer stamped the request's arrival the budget is measured
    from it, so a run queued behind a busy worker pool cannot outlive the
    platform's own timeout (#555)."""
    deadline = Deadline(budget_s, clock=clock, started_at=_arrived_at.get())
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


class DeadlineTransport(httpx.BaseTransport):
    """An httpx transport that bounds each request — headers and body — to the
    run deadline (#554).

    `invoke_within_deadline` stops waiting at the deadline but the abandoned
    thread's in-flight model call kept running — and billing — through the
    SDK's own retries. Here each send runs on a daemon thread waited on only
    for the budget left; on expiry the inner pool is closed so the abandoned
    request's socket dies with it, and every later attempt fails the instant
    `check` runs. Nothing keeps spending once the run has failed.
    """

    def __init__(self, inner: httpx.BaseTransport | None = None) -> None:
        self._inner = inner or httpx.HTTPTransport()

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        current = _current.get()
        if current is None:
            return self._inner.handle_request(request)
        current.check("model request")
        outcome: dict[str, Any] = {}
        done = threading.Event()

        def target() -> None:
            try:
                response = self._inner.handle_request(request)
                # The body is lazy — read it inside the budget or a truncated
                # generation would stream on after the run failed.
                response.read()
                outcome["response"] = response
            except BaseException as e:  # noqa: BLE001 - re-raised on the caller
                outcome["error"] = e
            finally:
                done.set()

        threading.Thread(target=target, name="octo-model-request", daemon=True).start()
        if done.wait(timeout=current.remaining()):
            if "error" in outcome:
                raise outcome["error"]
            return outcome["response"]
        with contextlib.suppress(Exception):
            self._inner.close()
        raise DeadlineExceeded("model request completed")

    def close(self) -> None:
        self._inner.close()


def invoke_within_deadline(runnable: Any, payload: Any) -> Any:
    """Runs `runnable.invoke(payload)` on a daemon thread in a copy of this
    context, and stops waiting at the deadline. An abandoned call dies at its
    next model or tool step; drafters have no write path, so nothing lands."""
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
        raise DeadlineExceeded("drafter call completed")
    if "error" in outcome:
        raise outcome["error"]
    return outcome["value"]
