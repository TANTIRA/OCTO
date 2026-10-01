"""F5 — IC memo drafting: a passing memo opens the prospect's ic-review task,
a 409 there leaves a judged draft, and a refused gate writes nothing. Judge
faked at the transport layer, deepagent stubbed — same pattern as the other
workflow tests."""

import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from octo_agents.api_client import OctoApiError
from octo_agents.judge import JudgeClient
from octo_agents.workflows import ic_memo
from octo_agents.workflows.ic_memo import run_ic_memo

from .fakes import StrictFake


class FakeApi(StrictFake):
    def __init__(self, *, ic_status: int = 202, events: list[Any] | None = None) -> None:
        self.ic_status = ic_status
        self.events = events or []
        self.ic_reviews: list[str] = []
        self.finished: dict[str, Any] = {}
        self.warm_context: str | None = None

    def get_agent_context(self, tenant_id: str) -> Any:
        return {"warmContext": self.warm_context}

    def get_prospect(self, prospect_id: str) -> Any:
        return {"id": prospect_id, "tenantId": "t-1", "stage": "ic-review", "name": "PT Acme"}

    def list_prospect_events(self, prospect_id: str) -> Any:
        return self.events

    def request_ic_review(self, prospect_id: str) -> Any:
        self.ic_reviews.append(prospect_id)
        if self.ic_status == 409:
            raise OctoApiError(409, "prospect is not at ic-review")
        return {"taskId": "ic-task-1"}

    def record_run(self, **kwargs: Any) -> Any:
        return {"id": "run-1"}

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        self.finished = kwargs
        return {}


def fake_judge(*, preflight: float, complete: float, evidence: float, thesis: str) -> JudgeClient:
    def handler(request: httpx.Request) -> httpx.Response:
        keys = json.loads(request.content)["questions"].keys()
        if "sufficient" in keys:
            answers: dict[str, Any] = {
                "sufficient": {"noul": preflight},
                "gap": {"choice": "none", "probabilities": {}, "confidence": 0.8},
            }
        elif any(k.startswith("relevance_") for k in keys):
            answers = {
                k: {"score": 4.0, "legend": {}, "probabilities": {}, "confidence": 0.9}
                for k in keys
            }
        else:
            answers = {
                "complete": {"noul": complete},
                "thesis": {"choice": thesis, "probabilities": {}, "confidence": 0.8},
                "evidence": {"score": evidence, "legend": {}, "probabilities": {}, "confidence": 0.9},
            }
        return httpx.Response(
            200,
            json={
                "model": "typesafe/jev-1.13",
                "provider": "typesafe",
                "id": "req-test",
                "answers": answers,
            },
        )

    return JudgeClient(
        endpoint="https://openrouter.ai/api/alpha/decisions",
        api_key="k",
        model="typesafe/jev-1.13",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )


def stub_drafter(monkeypatch: pytest.MonkeyPatch, memo: str = "ic memo prose") -> None:
    class FakeAgent:
        def invoke(self, payload: dict) -> dict:
            return {"messages": [SimpleNamespace(content=memo)]}

    monkeypatch.setattr(ic_memo, "create_deep_agent", lambda **kwargs: FakeAgent())


MODELS = {"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"}


def test_passing_memo_opens_the_ic_review_task(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi(events=[{"note": "deck"}])
    stub_drafter(monkeypatch)
    result = run_ic_memo(
        agent_model=None,
        judge=fake_judge(preflight=0.9, complete=0.9, evidence=4.0, thesis="aligned"),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models=MODELS,
    )
    assert result.status == "completed"
    assert result.memo == "ic memo prose"
    assert result.ic_review_requested and result.ic_review_task_id == "ic-task-1"
    assert api.ic_reviews == ["p-1"]
    assert api.finished["status"] == "completed"


def test_gate_pass_but_wrong_stage_leaves_a_judged_draft(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi(ic_status=409)
    stub_drafter(monkeypatch)
    result = run_ic_memo(
        agent_model=None,
        judge=fake_judge(preflight=0.9, complete=0.9, evidence=4.0, thesis="aligned"),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models=MODELS,
    )
    assert result.status == "completed"
    assert not result.ic_review_requested
    assert result.stage_note and "ic-review" in result.stage_note


def test_failing_gate_submits_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()
    stub_drafter(monkeypatch)
    result = run_ic_memo(
        agent_model=None,
        judge=fake_judge(preflight=0.9, complete=0.4, evidence=2.0, thesis="off-thesis"),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models=MODELS,
    )
    assert result.status == "completed"  # the run completed — the verdict refused the memo
    assert not result.ic_review_requested
    assert api.ic_reviews == []


def test_warm_context_reaches_the_drafter_prompt(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()
    api.warm_context = "We are a healthcare-specialist fund; thesis excludes fintech."
    captured: dict[str, Any] = {}

    class FakeAgent:
        def invoke(self, payload: dict) -> dict:
            return {"messages": [SimpleNamespace(content="memo")]}

    monkeypatch.setattr(
        ic_memo,
        "create_deep_agent",
        lambda **kwargs: captured.update(kwargs) or FakeAgent(),
    )
    run_ic_memo(
        agent_model=None,
        judge=fake_judge(preflight=0.9, complete=0.9, evidence=4.0, thesis="aligned"),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-warm",
        models=MODELS,
    )
    assert "healthcare-specialist fund" in captured["system_prompt"]


def test_preflight_refusal_spends_no_drafter(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()
    monkeypatch.setattr(
        ic_memo,
        "create_deep_agent",
        lambda **kwargs: pytest.fail("drafter ran on a refused record"),
    )
    result = run_ic_memo(
        agent_model=None,
        judge=fake_judge(preflight=0.1, complete=0.0, evidence=0.0, thesis="aligned"),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models=MODELS,
    )
    assert result.status == "refused"
    assert api.ic_reviews == []
    assert api.finished["status"] == "refused"
