"""F7 — company-brain query: the lane gate refuses unanswerable questions
before the drafter runs; the answer gate ships only responsive, supported
drafts. Judge faked at the transport layer; the deepagent stubbed."""

import json
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

from octo_agents.judge import JudgeClient
from octo_agents.workflows import company_brain
from octo_agents.workflows.company_brain import run_company_brain

from .fakes import StrictFake


class FakeApi(StrictFake):
    def __init__(self) -> None:
        self.pipeline_reads: list[str] = []
        self.finished: dict[str, Any] = {}

    def get_prospect(self, prospect_id: str) -> Any:
        return {"id": prospect_id, "stage": "ic-review"}

    def list_pipeline(self, tenant_id: str, stage: str, limit: int = 50) -> Any:
        self.pipeline_reads.append(stage)
        return [{"id": "p-1", "name": "PT Acme"}]

    def get_agent_context(self, tenant_id: str) -> Any:
        return {"warmContext": None}

    def record_run(self, **kwargs: Any) -> Any:
        return {"id": "run-1"}

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        self.finished = kwargs
        return {}


def fake_judge(*, answerable: float, answers: float, supported: float) -> JudgeClient:
    def handler(request: httpx.Request) -> httpx.Response:
        keys = json.loads(request.content)["questions"].keys()
        if "answerable" in keys:
            out: dict[str, Any] = {
                "answerable": {"noul": answerable},
                "lane": {"choice": "pipeline", "probabilities": {}, "confidence": 0.8},
            }
        else:
            out = {
                "answers": {"noul": answers},
                "supported": {"noul": supported},
            }
        return httpx.Response(
            200,
            json={
                "model": "typesafe/jev-1.13",
                "provider": "typesafe",
                "id": "req-test",
                "answers": out,
            },
        )

    return JudgeClient(
        endpoint="https://openrouter.ai/api/alpha/decisions",
        api_key="k",
        model="typesafe/jev-1.13",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )


def stub_drafter(monkeypatch: pytest.MonkeyPatch, answer: str = "p-1 stands at ic-review") -> None:
    class FakeAgent:
        def invoke(self, payload: dict) -> dict:
            return {"messages": [SimpleNamespace(content=answer)]}

    monkeypatch.setattr(company_brain, "create_deep_agent", lambda **kwargs: FakeAgent())


MODELS = {"drafter": "deepseek/deepseek-v4.1-flash", "judge": "typesafe/jev-1.13"}


def run(api: FakeApi, judge: JudgeClient) -> Any:
    return run_company_brain(
        agent_model=None,
        judge=judge,
        api=api,
        tenant_id="t-1",
        question="who is at ic-review?",
        run_key="rk-1",
        models=MODELS,
    )


def test_supported_answer_ships(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()
    stub_drafter(monkeypatch)
    result = run(api, fake_judge(answerable=0.9, answers=0.9, supported=0.9))
    assert result.status == "completed"
    assert result.answer == "p-1 stands at ic-review"
    assert api.finished["status"] == "completed"


def test_unanswerable_question_never_runs_the_drafter(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()
    monkeypatch.setattr(
        company_brain,
        "create_deep_agent",
        lambda **kwargs: pytest.fail("drafter ran on an out-of-scope question"),
    )
    result = run(api, fake_judge(answerable=0.1, answers=0.0, supported=0.0))
    assert result.status == "refused"
    assert api.pipeline_reads == []
    assert api.finished["status"] == "refused"


def test_unsupported_draft_is_refused_not_shipped(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()
    stub_drafter(monkeypatch, answer="confident hallucination")
    result = run(api, fake_judge(answerable=0.9, answers=0.9, supported=0.2))
    assert result.status == "refused"
    assert result.note
    assert api.finished["status"] == "refused"


def test_pipeline_tool_lists_only_known_stages() -> None:
    api = FakeApi()
    tool = company_brain._pipeline_tool(api, "t-1")
    assert "p-1" in tool.invoke("ic-review")
    assert "unknown stage" in tool.invoke("bogus")
    assert api.pipeline_reads == ["ic-review"]
