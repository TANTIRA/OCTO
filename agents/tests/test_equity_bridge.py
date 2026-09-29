"""F6 — equity-bridge quarterly analysis: the drafter narrates the platform's
computed bridge with no tools; the jev citation gate decides whether the
analysis ships. Judge faked at the transport layer; the drafter is a stub."""

from types import SimpleNamespace
from typing import Any

import httpx

from octo_agents.api_client import OctoApiClient
from octo_agents.judge import JudgeClient
from octo_agents.workflows.equity_bridge import run_equity_bridge


class FakeApi(OctoApiClient):
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
    def __init__(self, analysis: str) -> None:
        self.analysis = analysis

    def invoke(self, messages: list[Any]) -> Any:
        return SimpleNamespace(content=self.analysis)


def fake_judge(*, cited: float, complete: float, register: str) -> JudgeClient:
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
                    "register": {
                        "choice": register,
                        "probabilities": {},
                        "confidence": 0.8,
                    },
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
ANALYSIS = (
    "Revenue added 18.5m, margin cost 2.0m, multiple 4.0m, net debt 3.0m: "
    "equity value rose 17.5m in reporting currency."
)
ENTRY = {
    "date": "2025-09-30",
    "revenue": "50",
    "margin": "0.20",
    "multiple": "8.0",
    "net_debt": "30",
    "fx_rate": "1",
}
EXIT = {
    "date": "2026-09-30",
    "revenue": "60",
    "margin": "0.22",
    "multiple": "8.5",
    "net_debt": "20",
    "fx_rate": "1",
}
EFFECTS = {
    "REVENUE": "18.5",
    "MARGIN": "2.0",
    "MULTIPLE": "4.0",
    "NET_DEBT": "3.0",
    "FX": "0",
}
CHANGE = "17.5"


def run(api: FakeApi, judge: JudgeClient, analysis: str = ANALYSIS) -> Any:
    return run_equity_bridge(
        agent_model=FakeModel(analysis),
        judge=judge,
        api=api,
        tenant_id="t-1",
        company="fund-1",
        entry=ENTRY,
        exit=EXIT,
        effects=EFFECTS,
        change=CHANGE,
        method="Sequential[REVENUE, MARGIN, MULTIPLE, NET_DEBT, FX]",
        local_currency="USD",
        reporting_currency="USD",
        run_key="rk-1",
        models=MODELS,
    )


def test_cited_analysis_ships() -> None:
    api = FakeApi()
    result = run(api, fake_judge(cited=0.9, complete=0.9, register="memo-ready"))
    assert result.status == "completed"
    assert "17.5" in result.analysis
    assert api.finished["status"] == "completed"
    assert api.calls == []  # no tenant reads — the computed bridge is inline


def test_mis_cited_analysis_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(cited=0.3, complete=0.9, register="memo-ready"))
    assert result.status == "refused"
    assert result.analysis  # the draft stays auditable on agent_run
    assert api.finished["status"] == "refused"


def test_internal_register_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(cited=0.9, complete=0.9, register="internal"))
    assert result.status == "refused"
    assert api.finished["status"] == "refused"


def test_no_effects_refuses_without_a_drafter_call() -> None:
    api = FakeApi()

    class FailModel:
        def invoke(self, messages: list[Any]) -> Any:
            raise AssertionError("drafter ran with no effects")

    result = run_equity_bridge(
        agent_model=FailModel(),
        judge=fake_judge(cited=0.0, complete=0.0, register="internal"),
        api=api,
        tenant_id="t-1",
        company="fund-1",
        entry=ENTRY,
        exit=EXIT,
        effects={},
        change=CHANGE,
        method="Sequential[REVENUE, MARGIN, MULTIPLE, NET_DEBT, FX]",
        local_currency="USD",
        reporting_currency="USD",
        run_key="rk-1",
        models=MODELS,
    )
    assert result.status == "refused"
    assert "no bridge effects" in (result.note or "")
    assert api.finished["status"] == "refused"
