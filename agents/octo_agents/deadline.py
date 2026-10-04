"""End-to-end run deadline (#486, #555).

The platform gives up on a workflow call after 120 s (AgentsClient.kt). A run
holds one monotonic deadline, kept in a contextvar. The clock starts when the
request arrives (`note_request_arrival` in server.py), not when a threadpool
worker picks it up, so time spent queued behind other runs counts against the
budget. Every judge and platform attempt checks the deadline and caps its HTTP
timeout to the time left (retry.py). Every drafter call checks it at each
model or tool step (`invoke_within_deadline`). `finish_failed` still records
the failure in a short grace window. The budget plus the grace window stays
under 120 s, so the run is recorded `failed` before the platform gives up.
"""

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
    def __init__(self, budget_s: float, *, clock: Callable[[], float] = time.monotonic) -> None:
        self.clock = clock
        self._expires_at = clock() + budget_s

    def remaining(self) -> float:
        return max(0.0, self._expires_at - self.clock())

    def check(self, stage: str) -> None:
        if self.remaining() <= 0:
            raise DeadlineExceeded(stage)


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


# When the HTTP request was accepted. Sync endpoints wait for a free threadpool
# worker; that wait is not part of the worker's own clock (#555).
_arrived_at: contextvars.ContextVar[float | None] = contextvars.ContextVar(
    "octo_request_arrived_at", default=None
)


@contextmanager
def note_request_arrival(*, clock: Callable[[], float] = time.monotonic) -> Iterator[None]:
    """Records acceptance time, before the request waits for a worker."""
    token = _arrived_at.set(clock())
    try:
        yield
    finally:
        _arrived_at.reset(token)


def budget_after_queue(budget_s: float, *, clock: Callable[[], float] = time.monotonic) -> float:
    """Seconds of `budget_s` still left when a worker starts.

    Time since `note_request_arrival` counts against the budget. With no
    arrival mark — a direct call, or a test — the full budget remains.
    """
    arrived = _arrived_at.get()
    if arrived is None:
        return budget_s
    return budget_s - (clock() - arrived)


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
