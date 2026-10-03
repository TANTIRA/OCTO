"""#486 — one end-to-end run deadline gates every outbound attempt and drafter
step, caps in-flight HTTP timeouts, and still lets the run land `failed`.
#548/#554/#555 — ambiguous task writes are recorded as unknown, abandoned
model calls are killed at the socket, and the budget counts queue time."""

import threading
import time
from typing import Any

import httpx
import pytest
from langchain_core.language_models.fake_chat_models import FakeListChatModel

from octo_agents import deadline
from octo_agents.api_client import OctoApiClient, PlatformWriteOutcomeUnknown
from octo_agents.deadline import (
    Deadline,
    DeadlineExceeded,
    DeadlineTransport,
    _DeadlineCallback,
    invoke_within_deadline,
    run_deadline,
)
from octo_agents.retry import send_with_retry
from octo_agents.workflows import screening_dd
from octo_agents.workflows.screening_dd import finish_failed, run_screening_dd

from .test_screening_dd import FakeApi, fake_judge


class FakeClock:
    def __init__(self) -> None:
        self.now = 1_000.0

    def __call__(self) -> float:
        return self.now


def test_deadline_counts_down_on_the_injected_clock() -> None:
    clock = FakeClock()
    d = Deadline(100, clock=clock)
    d.check("stage")  # within budget: no raise
    clock.now += 100
    assert d.remaining() == 0
    with pytest.raises(DeadlineExceeded, match="before judge call"):
        d.check("judge call")


def test_outbound_request_is_not_sent_once_the_run_is_out_of_time() -> None:
    clock = FakeClock()
    sent: list[int] = []

    def send() -> httpx.Response:
        sent.append(1)
        clock.now += 60  # a slow 503 burns the rest of the budget
        return httpx.Response(503)

    with run_deadline(100, clock=clock), pytest.raises(DeadlineExceeded):
        send_with_retry(send, retries=5, sleep=lambda _: None)
    assert len(sent) == 2  # the third attempt would start past the deadline


def test_http_timeout_is_capped_to_the_time_left() -> None:
    client = httpx.Client(timeout=60.0)
    assert deadline.http_timeout(client) is httpx.USE_CLIENT_DEFAULT
    clock = FakeClock()
    with run_deadline(100, clock=clock):
        clock.now += 95
        capped = deadline.http_timeout(client)
    assert capped.read == 5 and capped.connect == 5 and capped.write == 5


def test_drafter_step_after_expiry_aborts_the_model_call() -> None:
    clock = FakeClock()
    expired = Deadline(0, clock=clock)
    model = FakeListChatModel(responses=["memo"])
    with pytest.raises(DeadlineExceeded, match="drafter model call"):
        model.invoke("draft", {"callbacks": [_DeadlineCallback(expired)]})


def test_hung_drafter_is_abandoned_at_the_deadline() -> None:
    release = threading.Event()

    class HungAgent:
        def invoke(self, payload: Any, config: Any) -> Any:
            release.wait(5)
            return {"messages": []}

    started = time.monotonic()
    with run_deadline(0.2), pytest.raises(DeadlineExceeded):
        invoke_within_deadline(HungAgent(), {"messages": []})
    assert time.monotonic() - started < 2
    release.set()


def test_drafter_results_and_errors_cross_back_to_the_workflow() -> None:
    with run_deadline(30):
        assert invoke_within_deadline(FakeListChatModel(responses=["memo"]), "x").content == "memo"
        with pytest.raises(ValueError, match="Invalid input type"):
            invoke_within_deadline(FakeListChatModel(responses=[]), 42)


def test_expired_run_still_records_failed_through_the_grace_window() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        return httpx.Response(200, json={})

    api = OctoApiClient(
        "http://api.test:8080", "tok", client=httpx.Client(transport=httpx.MockTransport(handler))
    )
    clock = FakeClock()
    with run_deadline(100, clock=clock):
        clock.now += 100
        with pytest.raises(DeadlineExceeded):
            api.get_agent_context("t-1")  # an ordinary call is refused...
        finish_failed(api, "run-1", DeadlineExceeded("judge call"))  # ...bookkeeping lands
    assert seen == ["/api/v1/agent-runs/run-1/finish"]


def test_screening_out_of_time_after_drafting_opens_no_task_and_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    clock = FakeClock()

    class SlowAgent:
        def invoke(self, payload: Any, config: Any) -> Any:
            clock.now += 120  # the drafter overran the whole budget
            return {"messages": [type("M", (), {"content": "memo"})()]}

    monkeypatch.setattr(screening_dd, "create_deep_agent", lambda **_: SlowAgent())
    api = FakeApi(events=[{"note": "deck"}])
    with run_deadline(100, clock=clock), pytest.raises(DeadlineExceeded):
        run_screening_dd(
            agent_model=None,
            judge=fake_judge(preflight=0.9, scores=[5.0], advance=0.95),
            api=api,
            prospect_id="p-1",
            tenant_id="t-1",
            run_key="rk-1",
            models={"drafter": "d", "judge": "j"},
        )
    assert api.screening_requests == []  # no task opened after the deadline
    assert [f["status"] for f in api.finished] == ["failed"]
    assert "deadline" in api.finished[0]["error"]


def test_deadline_is_measured_from_the_request_arrival() -> None:
    """#555 — queue time behind a busy worker counts against the budget."""
    clock = FakeClock()
    token = deadline.request_arrived(clock.now)
    try:
        clock.now += 30  # 30 s queued for a threadpool slot
        with run_deadline(100, clock=clock) as d:
            assert d.remaining() == 70
        token2 = deadline.request_arrived(clock.now)
        clock.now += 30  # the next request queued too
        with run_deadline(100, clock=clock) as d2:
            assert d2.remaining() == 70  # its budget measures from its own arrival
        deadline.clear_arrival(token2)
    finally:
        deadline.clear_arrival(token)
    with run_deadline(100, clock=clock) as d3:
        assert d3.remaining() == 100  # no stamp: measured from pickup as before


class _FakeInner(httpx.BaseTransport):
    """An inner transport whose send can hang past the deadline."""

    def __init__(self) -> None:
        self.closed = False
        self.calls = 0
        self.slow = False

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        self.calls += 1
        if self.slow:
            time.sleep(2)
        return httpx.Response(200, json={"ok": True})

    def close(self) -> None:
        self.closed = True


def test_deadline_transport_passes_a_response_inside_the_budget() -> None:
    inner = _FakeInner()
    transport = DeadlineTransport(inner)
    with run_deadline(10):
        response = transport.handle_request(httpx.Request("POST", "http://m.test"))
    assert response.json() == {"ok": True}
    assert inner.calls == 1 and not inner.closed


def test_deadline_transport_kills_the_in_flight_request_at_expiry() -> None:
    inner = _FakeInner()
    inner.slow = True
    transport = DeadlineTransport(inner)
    started = time.monotonic()
    with run_deadline(0.2), pytest.raises(DeadlineExceeded, match="model request"):
        transport.handle_request(httpx.Request("POST", "http://m.test"))
    assert time.monotonic() - started < 1
    assert inner.closed  # the abandoned socket dies with the pool (#554)


def test_deadline_transport_refuses_a_send_after_expiry() -> None:
    inner = _FakeInner()
    transport = DeadlineTransport(inner)
    with run_deadline(0), pytest.raises(DeadlineExceeded, match="model request"):
        transport.handle_request(httpx.Request("POST", "http://m.test"))
    assert inner.calls == 0  # no spend after the run has failed


def test_a_sent_write_cut_at_the_deadline_is_ambiguous() -> None:
    """#548 — once the request fired, a deadline-cut answer is not "no task"."""
    clock = FakeClock()

    def handler(request: httpx.Request) -> httpx.Response:
        clock.now += 200  # the answer would arrive after the budget ran out
        raise httpx.ReadTimeout("timed out")

    api = OctoApiClient(
        "http://api.test:8080",
        "tok",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    with run_deadline(100, clock=clock), pytest.raises(
        PlatformWriteOutcomeUnknown, match="prospects/p-1/screen"
    ):
        api.request_screening("p-1")


def test_a_task_write_cut_at_the_deadline_records_outcome_unknown(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """#548 — the failed run must not omit a task the platform may have opened."""

    class MemoAgent:
        def invoke(self, payload: Any, config: Any) -> Any:
            return {"messages": [type("M", (), {"content": "memo"})()]}

    monkeypatch.setattr(screening_dd, "create_deep_agent", lambda **_: MemoAgent())

    class LateScreeningApi(FakeApi):
        def request_screening(self, prospect_id: str) -> Any:
            self.screening_requests.append(prospect_id)
            raise PlatformWriteOutcomeUnknown(f"/api/v1/prospects/{prospect_id}/screen")

    api = LateScreeningApi(events=[{"note": "deck"}])
    with run_deadline(100), pytest.raises(PlatformWriteOutcomeUnknown):
        run_screening_dd(
            agent_model=None,
            judge=fake_judge(preflight=0.9, scores=[5.0], advance=0.95),
            api=api,
            prospect_id="p-1",
            tenant_id="t-1",
            run_key="rk-1",
            models={"drafter": "d", "judge": "j"},
        )
    assert api.finished[0]["status"] == "failed"
    assert api.finished[0]["output"]["screening_outcome_unknown"] is True


def test_a_task_write_never_sent_fails_plain() -> None:
    """#548 — a send refused before it fired is not an ambiguous write."""
    clock = FakeClock()
    api = OctoApiClient(
        "http://api.test:8080",
        "tok",
        client=httpx.Client(
            transport=httpx.MockTransport(lambda r: httpx.Response(200, json={}))
        ),
    )
    with run_deadline(0, clock=clock):
        with pytest.raises(DeadlineExceeded) as exc:
            api.request_screening("p-1")
        assert not isinstance(exc.value, PlatformWriteOutcomeUnknown)
