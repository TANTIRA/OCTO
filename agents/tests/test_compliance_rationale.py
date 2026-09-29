"""F9 — compliance rationale: the drafter narrates the engine's outcomes with
no tools; jev's citation gate decides whether the rationale ships. Judge faked
at the transport layer; the drafter is a stub chat model."""

from types import SimpleNamespace
from typing import Any

import httpx

from octo_agents.judge import JudgeClient
from octo_agents.workflows.compliance_rationale import run_compliance_rationale


class FakeApi:
    """Only the run ledger is touched — the workflow reads no tenant data."""

    def __init__(self) -> None:
        self.finished: dict[str, Any] = {}
        self.calls: list[str] = []

    def __getattr__(self, name: str) -> Any:
        if name in ("record_run", "finish_run"):
            raise AttributeError(name)
        self.calls.append(name)
        raise AttributeError(name)

    def record_run(self, **kwargs: Any) -> Any:
        return {"id": "run-1"}

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        self.finished = kwargs
        return {}


class FakeModel:
    def invoke(self, messages: list[Any]) -> Any:
        return SimpleNamespace(content="conc breached: fraction 0.6 against limit 0.25")


def fake_judge(*, cited: float, complete: float) -> JudgeClient:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "typesafe/jev-1.13",
                "provider": "typesafe",
                "id": "req-test",
                "answers": {
                    "cited": {"noul": cited},
                    "complete": {"noul": complete},
                },
            },
        )

    return JudgeClient(
        endpoint="https://openrouter.ai/api/alpha/decisions",
        api_key="k",
        model="typesafe/jev-1.13",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )


MODELS = {"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"}
OUTCOMES = [
    {
        "rule_id": "conc",
        "version": "1",
        "result": "breach",
        "measured": {"fraction": "0.6"},
        "explanation": "60/100 exceeds 0.25",
        "task_id": "t-9",
        "recorded": "true",
    }
]


def run(api: FakeApi, judge: JudgeClient, outcomes: list = OUTCOMES) -> Any:
    return run_compliance_rationale(
        agent_model=FakeModel(),
        judge=judge,
        api=api,
        tenant_id="t-1",
        subject="fund-1",
        as_of="2026-06-30",
        outcomes=outcomes,
        run_key="rk-1",
        models=MODELS,
    )


def test_verified_rationale_ships() -> None:
    api = FakeApi()
    result = run(api, fake_judge(cited=0.9, complete=0.9))
    assert result.status == "completed"
    assert "0.6" in result.rationale
    assert api.finished["status"] == "completed"
    assert api.calls == []  # no tenant reads — inline outcomes only


def test_mis_cited_rationale_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(cited=0.3, complete=0.9))
    assert result.status == "refused"
    assert result.rationale  # the draft stays auditable on agent_run
    assert api.finished["status"] == "refused"


def test_no_outcomes_refuses_without_a_drafter_call() -> None:
    api = FakeApi()

    class FailModel:
        def invoke(self, messages: list[Any]) -> Any:
            raise AssertionError("drafter ran with no outcomes")

    result = run_compliance_rationale(
        agent_model=FailModel(),
        judge=fake_judge(cited=0.0, complete=0.0),
        api=api,
        tenant_id="t-1",
        subject="fund-1",
        as_of="2026-06-30",
        outcomes=[],
        run_key="rk-1",
        models=MODELS,
    )
    assert result.status == "refused"
    assert "no rule outcomes" in (result.note or "")
    assert api.finished["status"] == "refused"
