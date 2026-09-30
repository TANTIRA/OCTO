"""F1/F2 around the screening-dd flow: the pre-flight noul gates the drafter call
and per-event score questions bound what reaches its context. The judge is faked
at the transport layer (the same MockTransport test_judge uses); the deepagent is
stubbed to a canned memo so no model runs in unit tests."""

import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from octo_agents.api_client import OctoApiClient, OctoApiError
from octo_agents.judge import JudgeClient
from octo_agents.workflows import screening_dd
from octo_agents.workflows.screening_dd import run_screening_dd


class FakeApi(OctoApiClient):
    """Just enough OctoApiClient for the workflow — records the mediated writes."""

    def __init__(self, events: list[Any]) -> None:
        self.events = events
        self.screening_requests: list[str] = []
        self.finished: list[dict] = []

    def get_prospect(self, prospect_id: str) -> Any:
        return {"id": prospect_id, "stage": "screening", "name": "PT Acme"}

    def list_prospect_events(self, prospect_id: str) -> Any:
        return self.events

    def request_screening(self, prospect_id: str) -> Any:
        self.screening_requests.append(prospect_id)
        return {"verdict": "review"}

    def get_agent_context(self, tenant_id: str) -> Any:
        return {"warmContext": None}

    def record_run(self, **kwargs: Any) -> Any:
        return {"id": "run-1"}

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        self.finished.append({"run_id": run_id, **kwargs})
        return {}


def fake_judge(*, preflight: float, scores: list[float], advance: float) -> JudgeClient:
    """One MockTransport answering all three gates by the question keys sent."""

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        keys = body["questions"].keys()
        if "sufficient" in keys:
            answers: dict[str, Any] = {
                "sufficient": {"noul": preflight},
                "gap": {"choice": "none" if preflight >= 0.5 else "thin", "probabilities": {}, "confidence": 0.8},
            }
        elif any(k.startswith("relevance_") for k in keys):
            answers = {
                f"relevance_{i}": {"score": scores[i], "legend": {}, "probabilities": {}, "confidence": 0.9}
                for i in range(len(scores))
            }
        else:
            answers = {
                "advance": {"noul": advance},
                "rationale": {"choice": "evidence", "probabilities": {}, "confidence": 0.7},
                "quality": {"score": 4.0, "legend": {}, "probabilities": {}, "confidence": 0.9},
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


def fake_agent_factory(monkeypatch: pytest.MonkeyPatch, memo: str = "screening memo") -> list[dict]:
    """Swaps create_deep_agent for a canned memo; records the invoke payload."""
    invocations: list[dict] = []

    class FakeAgent:
        def invoke(self, payload: dict) -> dict:
            invocations.append(payload)
            return {"messages": [SimpleNamespace(content=memo)]}

    monkeypatch.setattr(
        screening_dd, "create_deep_agent", lambda **kwargs: FakeAgent()
    )
    return invocations


def test_preflight_refusal_spends_no_drafter_call_and_opens_nothing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    api = FakeApi(events=[{"kind": "registered"}])
    monkeypatch.setattr(
        screening_dd,
        "create_deep_agent",
        lambda **kwargs: pytest.fail("drafter ran on a refused record"),
    )
    result = run_screening_dd(
        agent_model=None,
        judge=fake_judge(preflight=0.2, scores=[], advance=0.0),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
    )
    assert result.status == "refused"
    assert result.memo == ""
    assert result.verdict is None
    assert result.preflight.probability == 0.2
    assert result.preflight.gap_band == "thin"
    assert api.screening_requests == []
    # F4: even a refused run lands its row with the verdict that refused it.
    assert api.finished[0]["status"] == "refused"
    assert api.finished[0]["verdict"]["sufficient"]["noul"] == 0.2


def test_sufficient_record_admits_only_relevant_events_then_screens(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events = [
        {"kind": "registered", "note": "sourced"},
        {"kind": "heartbeat"},
        {"kind": "document", "note": "deck"},
    ]
    api = FakeApi(events=events)
    invocations = fake_agent_factory(monkeypatch)
    result = run_screening_dd(
        agent_model=None,
        judge=fake_judge(preflight=0.9, scores=[5.0, 2.0, 4.0], advance=0.85),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
    )
    assert result.status == "completed"
    assert result.memo == "screening memo"
    assert result.retrieval is not None
    assert result.retrieval.events_total == 3
    assert result.retrieval.events_admitted == 2
    # The drafter's task carries the two admitted events, never the dropped one.
    task_text = invocations[0]["messages"][0][1]
    assert "sourced" in task_text and "deck" in task_text
    assert "heartbeat" not in task_text
    assert result.verdict is not None and result.verdict.proceed
    assert api.screening_requests == ["p-1"]
    assert api.finished[0]["status"] == "completed"
    assert api.finished[0]["output"]["memo"] == "screening memo"


def test_all_events_below_bar_still_admits_the_top_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    api = FakeApi(events=[{"note": "a"}, {"note": "b"}])
    fake_agent_factory(monkeypatch)
    result = run_screening_dd(
        agent_model=None,
        judge=fake_judge(preflight=0.9, scores=[1.0, 2.0], advance=0.3),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
    )
    assert result.retrieval is not None and result.retrieval.events_admitted == 1
    assert result.verdict is not None and not result.verdict.proceed
    assert api.screening_requests == []


def test_screening_conflict_degrades_to_judged_draft(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class ConflictApi(FakeApi):
        def request_screening(self, prospect_id: str) -> Any:
            self.screening_requests.append(prospect_id)
            raise OctoApiError(409, "task already open")

    api = ConflictApi(events=[{"note": "deck"}])
    fake_agent_factory(monkeypatch)
    result = run_screening_dd(
        agent_model=None,
        judge=fake_judge(preflight=0.9, scores=[5.0], advance=0.85),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
    )
    assert result.status == "completed"
    assert result.screening_response is None
    # A 409 means nothing was requested — same contract as ic_memo (#330).
    assert result.screening_requested is False
    assert result.stage_note is not None and "not at screening" in result.stage_note
    assert api.finished[0]["status"] == "completed"


def test_screening_non_conflict_error_still_fails_the_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FailApi(FakeApi):
        def request_screening(self, prospect_id: str) -> Any:
            raise OctoApiError(500, "server error")

    api = FailApi(events=[{"note": "deck"}])
    fake_agent_factory(monkeypatch)
    with pytest.raises(OctoApiError):
        run_screening_dd(
            agent_model=None,
            judge=fake_judge(preflight=0.9, scores=[5.0], advance=0.85),
            api=api,
            prospect_id="p-1",
            tenant_id="t-1",
            run_key="rk-1",
            models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
        )
    assert api.finished[0]["status"] == "failed"


def test_empty_history_passes_preflight_with_an_empty_evidence_block(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    api = FakeApi(events=[])
    invocations = fake_agent_factory(monkeypatch)
    result = run_screening_dd(
        agent_model=None,
        judge=fake_judge(preflight=0.9, scores=[], advance=0.4),
        api=api,
        prospect_id="p-1",
        tenant_id="t-1",
        run_key="rk-1",
        models={"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"},
    )
    assert result.retrieval is not None and result.retrieval.events_total == 0
    assert "(no events on record)" in invocations[0]["messages"][0][1]
