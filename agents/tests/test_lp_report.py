"""F8 — LP report drafting: the drafter narrates the job's inline facts with
no tool access; the jev gate decides whether the job may go done. Judge faked
at the transport layer; the drafter is a stub chat model."""

from types import SimpleNamespace
from typing import Any

import httpx

from octo_agents.judge import JudgeClient
from octo_agents.workflows.lp_report import run_lp_report

from .fakes import StrictFake


class FakeApi(StrictFake):
    """Only the run ledger is touched — the workflow reads no tenant data."""

    def __init__(self) -> None:
        self.finished: dict[str, Any] = {}
        self.calls: list[str] = []

    def __getattr__(self, name: str) -> Any:
        if name in ("record_run", "finish_run"):
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


class FakeModel:
    def __init__(self, memo: str) -> None:
        self.memo = memo

    def invoke(self, messages: list[Any]) -> Any:
        return SimpleNamespace(content=self.memo)


def fake_judge(*, complete: float, supported: float, tone: str) -> JudgeClient:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "typesafe/jev-1.13",
                "provider": "typesafe",
                "id": "req-test",
                "answers": {
                    "complete": {"noul": complete},
                    "supported": {"noul": supported},
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
PARAMS = {"currency": "USD", "valuationDate": "2026-06-30", "nav": "60", "flows": []}


def run(api: FakeApi, judge: JudgeClient) -> Any:
    return run_lp_report(
        agent_model=FakeModel("quarterly letter prose"),
        judge=judge,
        api=api,
        job_id="job-1",
        tenant_id="t-1",
        run_key="report-job:job-1",
        position_source_type="inline-series",
        position_source_id="fund-1",
        measures=["tvpi", "dpi"],
        parameters=PARAMS,
        models=MODELS,
    )


def test_supported_letter_completes_with_the_memo_as_artifact() -> None:
    api = FakeApi()
    result = run(api, fake_judge(complete=0.9, supported=0.9, tone="lp-ready"))
    assert result.status == "completed"
    assert result.memo == "quarterly letter prose"
    assert api.finished["status"] == "completed"
    assert api.finished["output"]["memo"] == "quarterly letter prose"
    assert api.calls == []  # no tenant reads — inline facts only


def test_unsupported_letter_is_refused_not_shipped() -> None:
    api = FakeApi()
    result = run(api, fake_judge(complete=0.9, supported=0.3, tone="lp-ready"))
    assert result.status == "refused"
    assert result.memo  # the draft stays auditable on agent_run
    assert result.stage_note
    assert api.finished["status"] == "refused"


def test_internal_tone_is_refused() -> None:
    api = FakeApi()
    result = run(api, fake_judge(complete=0.9, supported=0.9, tone="internal"))
    assert result.status == "refused"


def test_replayed_run_returns_the_stored_output() -> None:
    class ReplayApi(FakeApi):
        def record_run(self, **kwargs: Any) -> Any:
            return {
                "id": "run-1",
                "status": "completed",
                "output": {"job_id": "job-1", "status": "completed", "memo": "stored"},
            }

    api = ReplayApi()
    result = run(api, fake_judge(complete=0.0, supported=0.0, tone="internal"))
    assert result.memo == "stored"
    assert api.finished == {}
