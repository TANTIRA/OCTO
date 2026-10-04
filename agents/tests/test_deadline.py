"""#486 — one end-to-end run deadline gates every outbound attempt and drafter
step, caps in-flight HTTP timeouts, and still lets the run land `failed`."""

import threading
import time
from typing import Any

import httpx
import pytest
from langchain_core.language_models.fake_chat_models import FakeListChatModel

from octo_agents import deadline
from octo_agents.api_client import OctoApiClient
from octo_agents.chat import drafter_model
from octo_agents.config import Settings
from octo_agents.deadline import (
    Deadline,
    DeadlineExceeded,
    _DeadlineCallback,
    _DeadlineTransport,
    drafter_request_overrides,
    invoke_within_deadline,
    run_deadline,
    track_model_client,
)
from octo_agents.registry import ApprovedModelRegistry
from octo_agents.retry import send_with_retry
from octo_agents.workflows import screening_dd
from octo_agents.workflows.screening_dd import finish_failed, run_screening_dd

from .test_screening_dd import FakeApi, fake_judge


class FakeClock:
    def __init__(self) -> None:
        self.now = 1_000.0

    def __call__(self) -> float:
        return self.now


def test_queue_time_comes_out_of_the_budget() -> None:
    clock = FakeClock()
    assert deadline.budget_after_queue(100, clock=clock) == 100  # no arrival mark
    with deadline.note_request_arrival(clock=clock):
        assert deadline.budget_after_queue(100, clock=clock) == 100
        clock.now += 30  # the request sat in the threadpool queue
        left = deadline.budget_after_queue(100, clock=clock)
        assert left == 70
        with run_deadline(left, clock=clock) as active:
            assert active.remaining() == 70
            clock.now += 70
            with pytest.raises(DeadlineExceeded, match="before judge call"):
                active.check("judge call")
    assert deadline.budget_after_queue(100, clock=clock) == 100


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


class _CountingTransport(httpx.BaseTransport):
    """Records each send, then fails the way a slow provider times out."""

    def __init__(self, clock: FakeClock) -> None:
        self.clock = clock
        self.timeouts: list[float] = []

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        timeout = request.extensions["timeout"]
        self.timeouts.append(float(timeout.read))
        self.clock.now += 60
        raise httpx.ReadTimeout("slow provider")


def test_model_attempt_is_capped_and_not_retried_after_the_deadline() -> None:
    # #554 — a single-step drafter never reaches another callback, so the
    # SDK's own retries used to run (and bill) for minutes after the run failed.
    clock = FakeClock()
    inner = _CountingTransport(clock)
    transport = _DeadlineTransport(inner, owner=None)
    request = httpx.Request("POST", "https://openrouter.ai/api/v1/chat/completions")
    request.extensions["timeout"] = httpx.Timeout(60.0)

    def sdk_retry() -> None:
        for _ in range(3):  # max_retries=2 → three attempts
            try:
                transport.handle_request(request)
            except httpx.ReadTimeout:
                continue
            return

    with run_deadline(10, clock=clock), pytest.raises(DeadlineExceeded, match="drafter model call"):
        sdk_retry()
    assert inner.timeouts == [10.0]


def test_abandoned_drafter_closes_the_inflight_client() -> None:
    closed = threading.Event()

    class Client:
        def close(self) -> None:
            closed.set()

    class Hung:
        def invoke(self, payload: Any, config: Any) -> Any:
            if not closed.wait(5):
                raise AssertionError("in-flight model call was not cancelled")
            return {"messages": []}

    started = time.monotonic()
    with run_deadline(0.2), pytest.raises(DeadlineExceeded):
        track_model_client(Client())
        invoke_within_deadline(Hung(), {})
    assert closed.is_set()
    assert time.monotonic() - started < 2


def test_drafter_retry_budget_is_the_time_left_not_the_sdk_window() -> None:
    assert drafter_request_overrides(60_000) == {}
    clock = FakeClock()
    with run_deadline(100, clock=clock):
        clock.now += 40
        limits = drafter_request_overrides(60_000)
    assert limits["timeout_ms"] == 60_000  # configured attempt cap is tighter
    assert limits["retries"].backoff.max_elapsed_time == 60_000
    clock = FakeClock()
    with run_deadline(100, clock=clock):
        clock.now += 95
        limits = drafter_request_overrides(60_000)
    assert limits["timeout_ms"] == 5_000
    assert limits["retries"].backoff.max_elapsed_time == 5_000
    assert limits["retries"].backoff.max_interval == 5_000
    with run_deadline(100, clock=clock), pytest.raises(DeadlineExceeded):
        clock.now += 100
        drafter_request_overrides(60_000)


class _Completion:
    def model_dump(self, *, by_alias: bool = False) -> dict[str, Any]:
        return {
            "model": "deepseek/deepseek-v4.1-flash",
            "choices": [
                {
                    "message": {"role": "assistant", "content": "memo"},
                    "finish_reason": "stop",
                }
            ],
        }


def test_drafter_call_forwards_the_deadline_to_the_provider_client() -> None:
    settings = Settings(
        openrouter_api_key="k",
        octo_agents_insecure_http=True,
        request_timeout_s=60.0,
    )
    model = drafter_model(settings, ApprovedModelRegistry(settings.model_registry_path))
    http = model.client.sdk_configuration.client
    assert isinstance(http._transport, _DeadlineTransport)
    seen: dict[str, Any] = {}

    def send(**kwargs: Any) -> _Completion:
        seen.update(kwargs)
        return _Completion()

    model.client.chat.send = send  # type: ignore[method-assign]
    clock = FakeClock()
    with run_deadline(100, clock=clock):
        clock.now += 90
        assert model.invoke("draft the letter").content == "memo"
    assert seen["timeout_ms"] == 10_000
    assert seen["retries"].backoff.max_elapsed_time == 10_000
    with run_deadline(100, clock=clock), pytest.raises(DeadlineExceeded):
        clock.now += 100
        model.invoke("draft the letter")
    assert seen["timeout_ms"] == 10_000  # the expired call never reached the provider
