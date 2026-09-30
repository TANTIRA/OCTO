"""F10 — DDQ/RFP response: the drafter answers an LP questionnaire from the
supplied firm facts with no tools; jev's gate decides whether it ships. Judge
faked at the transport layer; the drafter is a stub chat model."""

from types import SimpleNamespace
from typing import Any

import httpx

from octo_agents.api_client import OctoApiClient
from octo_agents.judge import JudgeClient
from octo_agents.workflows.ddq_response import run_ddq_response


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

    # Signatures mirror OctoApiClient exactly — a production-side rename or
    # new required arg must break here, not pass silently through **kwargs.
    def record_run(
        self,
        *,
        tenant_id: str,
        workflow: str,
        run_key: str,
        subject_type: str,
        subject_id: str,
        input: Any,
        models: Any,
        thresholds: Any = None,
        request_ids: Any = None,
    ) -> Any:
        return {"id": "run-1"}

    def finish_run(
        self,
        run_id: str,
        *,
        status: str,
        output: Any = None,
        verdict: Any = None,
        error: str | None = None,
    ) -> Any:
        self.finished = {
            "status": status,
            "output": output,
            "verdict": verdict,
            "error": error,
        }
        return {}


class FakeModel:
    def __init__(self, response: str) -> None:
        self.response = response

    def invoke(self, messages: list[Any]) -> Any:
        return SimpleNamespace(content=self.response)


def fake_judge(*, grounded: float, answered: float, tone: str) -> JudgeClient:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "typesafe/jev-1.13",
                "provider": "typesafe",
                "id": "req-test",
                "answers": {
                    "grounded": {"noul": grounded},
                    "answered": {"noul": answered},
                    "tone": {"choice": tone, "probabilities": {}, "confidence": 0.8},
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
QUESTIONS = [
    "What is the fund's target net IRR?",
    "Describe the firm's ESG policy.",
]
FACTS = {
    "target_net_irr": "18%",
    "esg_policy": "screens out fossil-fuel extraction; annual review",
}
RESPONSE = (
    "1. The fund targets a net IRR of 18%.\n"
    "2. The firm screens out fossil-fuel extraction and reviews its ESG "
    "policy annually."
)


def run(
    api: FakeApi,
    judge: JudgeClient,
    response: str = RESPONSE,
    questions: list = QUESTIONS,
) -> Any:
    return run_ddq_response(
        agent_model=FakeModel(response),
        judge=judge,
        api=api,
        tenant_id="t-1",
        subject="ddq-acme-lp",
        questions=questions,
        facts=FACTS,
        run_key="rk-1",
        models=MODELS,
    )


def test_grounded_response_ships() -> None:
    api = FakeApi()
    result = run(api, fake_judge(grounded=0.9, answered=0.9, tone="lp-ready"))
    assert result.status == "completed"
    assert "18%" in result.response
    assert api.finished["status"] == "completed"
    assert api.calls == []  # no tenant reads — facts are inline


def test_ungrounded_response_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(grounded=0.3, answered=0.9, tone="lp-ready"))
    assert result.status == "refused"
    assert result.response  # the draft stays auditable on agent_run
    assert api.finished["status"] == "refused"


def test_incomplete_response_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(grounded=0.9, answered=0.4, tone="lp-ready"))
    assert result.status == "refused"
    assert api.finished["status"] == "refused"


def test_internal_tone_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(grounded=0.9, answered=0.9, tone="internal"))
    assert result.status == "refused"
    assert api.finished["status"] == "refused"


def test_no_questions_refuses_without_a_drafter_call() -> None:
    api = FakeApi()

    class FailModel:
        def invoke(self, messages: list[Any]) -> Any:
            raise AssertionError("drafter ran with no questions")

    result = run_ddq_response(
        agent_model=FailModel(),
        judge=fake_judge(grounded=0.0, answered=0.0, tone="internal"),
        api=api,
        tenant_id="t-1",
        subject="ddq-empty",
        questions=["   ", ""],
        facts=FACTS,
        run_key="rk-1",
        models=MODELS,
    )
    assert result.status == "refused"
    assert "no questions" in (result.note or "")
    assert api.finished["status"] == "refused"
