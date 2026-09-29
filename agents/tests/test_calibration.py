"""F12 — calibration: verdict-vs-human-outcome analysis over the run spine.
Deterministic, no model calls; agreed runs keep their direction, overridden
runs invert it and land in the disagreement queue plus eval cases."""

from typing import Any

from octo_agents.workflows.calibration import run_calibration


class FakeApi:
    def __init__(self, runs: list[dict[str, Any]]) -> None:
        self.runs = runs
        self.finished: dict[str, Any] = {}

    def list_agent_runs(self, tenant_id: str, **kwargs: Any) -> Any:
        return self.runs

    def record_run(self, **kwargs: Any) -> Any:
        return {"id": "cal-1"}

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        self.finished = kwargs
        return {}


def _run(
    run_id: str,
    workflow: str = "ic-memo",
    status: str = "completed",
    decision: str | None = None,
) -> dict[str, Any]:
    return {
        "id": run_id,
        "runKey": f"rk-{run_id}",
        "workflow": workflow,
        "subjectType": "prospect",
        "subjectId": "p-1",
        "status": status,
        "input": {"prospect_id": "p-1"},
        "verdict": {"complete": {"noul": 0.9}},
        "humanOutcome": {"decision": decision} if decision else None,
    }


def test_agreement_stats_and_eval_case_directions() -> None:
    api = FakeApi(
        [
            _run("a", decision="accepted"),   # shipped, agreed -> expect ship
            _run("b", decision="accepted", status="refused"),  # refused, agreed -> expect no-ship
            _run("c", decision="overridden"),  # shipped, overridden -> expect no-ship
            _run("d"),                        # no human outcome -> volume only
            _run("e", workflow="calibration", decision="rejected"),  # self — excluded
        ]
    )
    result = run_calibration(
        api=api, tenant_id="t-1", run_key="cal:1", models={}
    )
    assert result.status == "completed"
    assert result.analyzed == 4  # the calibration row never feeds itself
    stats = result.stats["ic-memo"]
    assert stats.runs == 4
    assert stats.decided == 3
    assert stats.agreed == 2
    assert stats.disagreed == 1

    by_case = {c.case: c for c in result.eval_cases}
    assert by_case["feedback:a"].expect_ship is True
    assert by_case["feedback:b"].expect_ship is False
    assert by_case["feedback:c"].expect_ship is False
    assert result.disagreements[0].run_id == "c"
    assert api.finished["status"] == "completed"


def test_enough_disagreement_suggests_a_threshold_review() -> None:
    api = FakeApi(
        [_run(str(i), decision="rejected") for i in range(4)]
        + [_run("ok", decision="accepted")]
    )
    result = run_calibration(api=api, tenant_id="t-1", run_key="cal:2", models={})
    assert result.stats["ic-memo"].agreement_rate == 0.2
    assert result.suggestions and "ic-memo" in result.suggestions[0]


def test_empty_history_completes_with_zero_decided() -> None:
    api = FakeApi([])
    result = run_calibration(api=api, tenant_id="t-1", run_key="cal:3", models={})
    assert result.status == "completed"
    assert result.analyzed == 0 and result.decided == 0
    assert result.suggestions == []


def test_replayed_run_returns_stored_output() -> None:
    class ReplayApi(FakeApi):
        def record_run(self, **kwargs: Any) -> Any:
            return {
                "id": "cal-1",
                "status": "completed",
                "output": {
                    "tenant_id": "t-1",
                    "status": "completed",
                    "analyzed": 9,
                    "decided": 0,
                    "stats": {},
                    "disagreements": [],
                    "eval_cases": [],
                    "suggestions": [],
                },
            }

    api = ReplayApi([])
    result = run_calibration(api=api, tenant_id="t-1", run_key="cal:4", models={})
    assert result.analyzed == 9
    assert api.finished == {}
