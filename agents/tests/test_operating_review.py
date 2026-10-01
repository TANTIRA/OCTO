"""F13 — operating-partner review: the drafter narrates a company's period
metrics against declared value-creation levers with no tools; jev's gate
decides whether it ships. Judge faked at the transport layer; the drafter is
a stub. Warm context is stubbed empty so the prompt is deterministic."""

from types import SimpleNamespace
from typing import Any

import httpx

from octo_agents.judge import JudgeClient
from octo_agents.workflows.operating_review import run_operating_review

from .fakes import StrictFake


class FakeApi(StrictFake):
    """Only the run ledger and warm-context read are touched."""

    def __init__(self) -> None:
        self.finished: dict[str, Any] = {}
        self.calls: list[str] = []

    def __getattr__(self, name: str) -> Any:
        if name in ("record_run", "finish_run", "get_agent_context"):
            raise AttributeError(name)
        self.calls.append(name)
        raise AttributeError(name)

    # StrictFake binds every call against OctoApiClient's real signature.
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

    def get_agent_context(self, tenant_id: str) -> Any:
        return {}  # no warm context — keeps the prompt deterministic


class FakeModel:
    def __init__(self, review: str) -> None:
        self.review = review

    def invoke(self, messages: list[Any]) -> Any:
        return SimpleNamespace(content=self.review)


def fake_judge(*, grounded: float, coverage: float, stance: str) -> JudgeClient:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "typesafe/jev-1.13",
                "provider": "typesafe",
                "id": "req-test",
                "answers": {
                    "grounded": {"noul": grounded},
                    "coverage": {"noul": coverage},
                    "stance": {
                        "choice": stance,
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
LEVERS = ["Grow recurring revenue", "Expand gross margin"]
METRICS = {
    "2025Q3": {"arr": "40", "gross_margin": "0.58"},
    "2026Q3": {"arr": "52", "gross_margin": "0.61"},
}
REVIEW = (
    "Grow recurring revenue: ARR rose from 40 to 52 — on track. "
    "Expand gross margin: 0.58 to 0.61, on track but modest."
)


def run(
    api: FakeApi, judge: JudgeClient, review: str = REVIEW, levers: list = LEVERS
) -> Any:
    return run_operating_review(
        agent_model=FakeModel(review),
        judge=judge,
        api=api,
        tenant_id="t-1",
        company="portco-atlas",
        levers=levers,
        metrics=METRICS,
        run_key="rk-1",
        models=MODELS,
    )


def test_grounded_review_ships() -> None:
    api = FakeApi()
    result = run(api, fake_judge(grounded=0.9, coverage=0.9, stance="analytical"))
    assert result.status == "completed"
    assert "52" in result.review
    assert api.finished["status"] == "completed"
    assert api.calls == []  # metrics inline; only the run ledger + warm context touched


def test_ungrounded_review_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(grounded=0.3, coverage=0.9, stance="analytical"))
    assert result.status == "refused"
    assert result.review
    assert api.finished["status"] == "refused"


def test_incomplete_lever_coverage_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(grounded=0.9, coverage=0.4, stance="analytical"))
    assert result.status == "refused"
    assert api.finished["status"] == "refused"


def test_prescriptive_stance_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(grounded=0.9, coverage=0.9, stance="prescriptive"))
    assert result.status == "refused"
    assert api.finished["status"] == "refused"


def test_no_levers_refuses_without_a_drafter_call() -> None:
    api = FakeApi()

    class FailModel:
        def invoke(self, messages: list[Any]) -> Any:
            raise AssertionError("drafter ran with no levers")

    result = run_operating_review(
        agent_model=FailModel(),
        judge=fake_judge(grounded=0.0, coverage=0.0, stance="prescriptive"),
        api=api,
        tenant_id="t-1",
        company="portco-empty",
        levers=["  ", ""],
        metrics=METRICS,
        run_key="rk-1",
        models=MODELS,
    )
    assert result.status == "refused"
    assert "no value-creation levers" in (result.note or "")
    assert api.finished["status"] == "refused"
