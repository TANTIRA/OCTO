"""F12 — calibration: verdict-vs-human-outcome analysis over the run spine.
Deterministic, no model calls; agreed runs keep their direction, overridden
runs invert it and land in the disagreement queue plus eval cases."""

import json
from typing import Any

from octo_agents.workflows.calibration import run_calibration

from .fakes import StrictFake


class FakeApi(StrictFake):
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


def test_unknown_decision_vocabulary_is_not_a_disagreement() -> None:
    api = FakeApi(
        [
            _run("a", decision="deferred"),  # not in AGREED/DISAGREED
            _run("b", decision="accepted"),
        ]
    )
    result = run_calibration(api=api, tenant_id="t-1", run_key="cal:5", models={})
    stats = result.stats["ic-memo"]
    assert stats.decided == 2
    assert stats.agreed == 1
    assert stats.disagreed == 0
    assert result.disagreements == []
    by_case = {c.case: c for c in result.eval_cases}
    assert by_case["feedback:a"].expect_ship is True
    assert by_case["feedback:b"].expect_ship is True


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


# #496 — the platform rejects run outputs over 32 KB, so calibration references
# runs by id and hard-caps its own output instead of failing its finish.
PLATFORM_MAX_OUTPUT_BYTES = 32_768


def _heavy_run(i: int, decision: str, workflow: str = "ic-memo") -> dict[str, Any]:
    run = _run(f"run-{i:04d}-" + "x" * 30, workflow=workflow, decision=decision)
    run["runKey"] = "k" * 200
    run["subjectId"] = "s" * 200
    run["input"] = {"prospect_id": "p-1", "notes": "n" * 20_000}
    run["verdict"] = {"memo": "v" * 20_000}
    return run


def test_two_hundred_heavy_runs_fit_the_platform_output_cap() -> None:
    runs = [_heavy_run(i, "rejected" if i % 2 else "accepted") for i in range(200)]
    api = FakeApi(runs)
    result = run_calibration(api=api, tenant_id="t-1", run_key="cal:6", models={})

    serialized = json.dumps(api.finished["output"])
    assert len(serialized.encode("utf-8")) < PLATFORM_MAX_OUTPUT_BYTES
    assert "n" * 100 not in serialized  # no run input is embedded
    assert "v" * 100 not in serialized  # no run verdict is embedded
    assert result.truncated is True
    # Totals and stats stay exact while the detail lists are capped.
    assert result.decided == 200
    assert result.disagreements_total == 100
    assert result.eval_cases_total == 200
    assert 0 < len(result.eval_cases) < 200
    assert result.stats["ic-memo"].disagreed == 100


def test_stats_are_trimmed_last_when_workflows_alone_overflow() -> None:
    # 200 runs (the page bound) cannot overflow on stats alone; a larger page
    # proves the cap still holds by dropping the least-run workflows.
    busiest = "wf-000-" + "w" * 56
    runs = [_heavy_run(i, "rejected", workflow=f"wf-{i:03d}-" + "w" * 56) for i in range(400)]
    runs.append(_heavy_run(999, "accepted", workflow=busiest))
    api = FakeApi(runs)
    result = run_calibration(api=api, tenant_id="t-1", run_key="cal:7", models={})

    serialized = json.dumps(api.finished["output"])
    assert len(serialized.encode("utf-8")) < PLATFORM_MAX_OUTPUT_BYTES
    assert result.truncated is True
    assert result.eval_cases == [] and result.disagreements == []
    assert 0 < len(result.stats) < 400
    assert busiest in result.stats  # the busiest workflow is kept


def test_small_history_is_not_truncated_and_references_runs_by_id() -> None:
    api = FakeApi([_run("a", decision="accepted"), _run("c", decision="overridden")])
    result = run_calibration(api=api, tenant_id="t-1", run_key="cal:8", models={})
    assert result.truncated is False
    assert {c.run_id for c in result.eval_cases} == {"a", "c"}
    assert "verdict" not in api.finished["output"]["disagreements"][0]
    assert "state" not in api.finished["output"]["eval_cases"][0]
