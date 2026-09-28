"""F3 — parallel DD subagents: jev bands each workstream and only high/blocker
streams mint evidence tasks. Judge faked at the transport layer; the deepagent
stubbed to a canned dossier — same pattern as test_screening_dd."""

import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from octo_agents.judge import JudgeClient
from octo_agents.workflows import due_diligence
from octo_agents.workflows.due_diligence import WORKSTREAMS, run_due_diligence


class FakeApi:
    def __init__(self, events: list[Any]) -> None:
        self.events = events
        self.evidence_requests: list[tuple[str, str]] = []

    def get_prospect(self, prospect_id: str) -> Any:
        return {"id": prospect_id, "stage": "due-diligence", "name": "PT Acme"}

    def list_prospect_events(self, prospect_id: str) -> Any:
        return self.events

    def open_dd_evidence(self, prospect_id: str, workstream: str, summary: str) -> Any:
        self.evidence_requests.append((prospect_id, workstream))
        return {"taskId": f"task-{workstream}", "opened": True}

    def record_run(self, **kwargs: Any) -> Any:
        return {"id": "run-1"}

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        return {}


def fake_judge(*, preflight: float, bands: dict[str, str]) -> JudgeClient:
    def handler(request: httpx.Request) -> httpx.Response:
        keys = json.loads(request.content)["questions"].keys()
        if "sufficient" in keys:
            answers: dict[str, Any] = {
                "sufficient": {"noul": preflight},
                "gap": {"choice": "none" if preflight >= 0.5 else "empty", "probabilities": {}, "confidence": 0.8},
            }
        elif any(k.startswith("relevance_") for k in keys):
            answers = {
                k: {"score": 4.0, "legend": {}, "probabilities": {}, "confidence": 0.9}
                for k in keys
            }
        else:
            answers = {
                ws: {"choice": bands[ws], "probabilities": {}, "confidence": 0.75}
                for ws in WORKSTREAMS
            }
            answers["completeness"] = {
                "score": 3.0,
                "legend": {},
                "probabilities": {},
                "confidence": 0.9,
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


def fake_agent_factory(monkeypatch: pytest.MonkeyPatch, dossier: str = "dd dossier") -> None:
    class FakeAgent:
        def invoke(self, payload: dict) -> dict:
            return {"messages": [SimpleNamespace(content=dossier)]}

    monkeypatch.setattr(due_diligence, "create_deep_agent", lambda **kwargs: FakeAgent())


def test_high_and_blocker_streams_mint_evidence_tasks(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    api = FakeApi(events=[{"note": "deck"}])
    fake_agent_factory(monkeypatch)
    result = run_due_diligence(
        agent_model=None,
        judge=fake_judge(
            preflight=0.9,
            bands={"market": "high", "financial": "blocker", "legal": "low", "operational": "medium"},
        ),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
    )
    assert result.status == "completed"
    assert api.evidence_requests == [("p-1", "market"), ("p-1", "financial")]
    assert [t.workstream for t in result.tasks] == ["market", "financial"]
    assert all(t.opened and t.task_id for t in result.tasks)
    assert {b.workstream: b.band for b in result.bands} == {
        "market": "high",
        "financial": "blocker",
        "legal": "low",
        "operational": "medium",
    }
    assert result.completeness == 3.0


def test_all_low_risk_streams_open_no_tasks(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi(events=[{"note": "deck"}])
    fake_agent_factory(monkeypatch)
    result = run_due_diligence(
        agent_model=None,
        judge=fake_judge(
            preflight=0.9,
            bands=dict.fromkeys(WORKSTREAMS, "low"),
        ),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
    )
    assert result.status == "completed"
    assert api.evidence_requests == []
    assert result.tasks == []


def test_preflight_refusal_runs_no_subagents_and_opens_nothing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    api = FakeApi(events=[])
    monkeypatch.setattr(
        due_diligence,
        "create_deep_agent",
        lambda **kwargs: pytest.fail("orchestrator ran on a refused record"),
    )
    result = run_due_diligence(
        agent_model=None,
        judge=fake_judge(preflight=0.1, bands={}),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
    )
    assert result.status == "refused"
    assert api.evidence_requests == []
