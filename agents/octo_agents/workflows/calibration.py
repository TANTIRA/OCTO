"""F12 — feedback loop: verdict-vs-reality calibration over the agent_run spine.

Reads the tenant's run history through the Kotlin edge and joins each finished
run's machine verdict against its `human_outcome` — the decision a member
recorded via POST /api/v1/agent-runs/{id}/outcome.

Convention: `human_outcome.decision` is "accepted" when the human agreed with
the machine's call and "rejected"/"overridden" when they did not. Runs without
a human outcome count toward volume but not agreement.

Output: per-workflow agreement stats, the disagreement list (the review queue
for threshold work), and eval-ready cases — each decided run exported as
`expect_ship`, i.e. whether the artifact should have shipped, so a disputed
verdict becomes a regression case for run_evals.py. Runs are referenced by id,
never embedded: the platform rejects run outputs over 32 KB, so the output is
hard-capped and marked `truncated` when detail had to be dropped (#496).

The analysis is deterministic — no drafter, no judge. The run still records on
the spine (workflow "calibration") and its own rows are excluded from analysis
so calibrating never feeds back into its own stats.
"""

import json
from typing import Any, Literal

from pydantic import BaseModel

from ..api_client import OctoApiClient
from .screening_dd import _record_run, finish_failed

WORKFLOW = "calibration"
MIN_DECIDED_FOR_SUGGESTION = 5
AGREEMENT_FLOOR = 0.8

# human_outcome.decision vocabulary — "accepted" means the human agreed with
# what the machine did; anything in DISAGREED means they would have decided
# the other way.
AGREED = {"accepted", "approved", "confirmed"}
DISAGREED = {"rejected", "overridden", "disagreed"}

# The platform caps a run's output at 32_768 bytes (AgentRunsController
# MAX_JSON_BYTES). Measured with json.dumps defaults — spaced separators and
# ASCII escapes, never smaller than the platform's compact UTF-8 — and with
# headroom on top.
MAX_OUTPUT_BYTES = 30_000
MAX_DISAGREEMENTS = 50
MAX_EVAL_CASES = 100


class WorkflowStats(BaseModel):
    runs: int
    decided: int
    agreed: int
    disagreed: int
    agreement_rate: float | None  # None until at least one human decision


class Disagreement(BaseModel):
    run_id: str
    run_key: str
    workflow: str
    subject: str
    status: str
    decision: str


class EvalCase(BaseModel):
    """A decided run re-exported for run_evals.py: `expect_ship` is whether the
    artifact should have shipped — accepted runs keep the machine's direction,
    disagreed runs invert it. The run's input is read back by `run_id` rather
    than copied here."""

    case: str
    workflow: str
    run_id: str
    expect_ship: bool


class CalibrationResult(BaseModel):
    tenant_id: str
    status: Literal["completed", "refused"]
    analyzed: int
    decided: int
    stats: dict[str, WorkflowStats]
    disagreements: list[Disagreement]
    eval_cases: list[EvalCase]
    suggestions: list[str]
    # Exact counts even when the lists above were capped to fit the run-output
    # limit; `truncated` is True whenever any list or stats entry was dropped.
    disagreements_total: int = 0
    eval_cases_total: int = 0
    truncated: bool = False


def _output_bytes(result: CalibrationResult) -> int:
    return len(json.dumps(result.model_dump()).encode("utf-8"))


def _keep_prefix(result: CalibrationResult, items: list[Any], apply: Any) -> None:
    """Applies the longest prefix of `items` (via `apply`) that keeps the output
    within MAX_OUTPUT_BYTES — a binary search, so a 200-run history costs a few
    serializations, not one per dropped entry."""
    lo, hi = 0, len(items)
    while lo < hi:
        mid = (lo + hi + 1) // 2
        apply(items[:mid])
        if _output_bytes(result) <= MAX_OUTPUT_BYTES:
            lo = mid
        else:
            hi = mid - 1
    apply(items[:lo])


def _fit(result: CalibrationResult) -> CalibrationResult:
    """Caps the output so the run's own finish is never rejected for size.
    Drops detail in order of least value: eval cases, then disagreements, then
    suggestions, then the smallest workflows' stats."""
    if len(result.eval_cases) > MAX_EVAL_CASES:
        result.eval_cases = result.eval_cases[:MAX_EVAL_CASES]
        result.truncated = True
    if len(result.disagreements) > MAX_DISAGREEMENTS:
        result.disagreements = result.disagreements[:MAX_DISAGREEMENTS]
        result.truncated = True
    if _output_bytes(result) <= MAX_OUTPUT_BYTES:
        return result
    result.truncated = True

    def set_eval_cases(kept: list[EvalCase]) -> None:
        result.eval_cases = kept

    def set_disagreements(kept: list[Disagreement]) -> None:
        result.disagreements = kept

    def set_suggestions(kept: list[str]) -> None:
        result.suggestions = kept

    def set_stats(kept: list[tuple[str, WorkflowStats]]) -> None:
        result.stats = dict(kept)

    ranked_stats = sorted(result.stats.items(), key=lambda kv: kv[1].runs, reverse=True)
    for items, apply in (
        (result.eval_cases, set_eval_cases),
        (result.disagreements, set_disagreements),
        (result.suggestions, set_suggestions),
        (ranked_stats, set_stats),
    ):
        if _output_bytes(result) <= MAX_OUTPUT_BYTES:
            break
        _keep_prefix(result, items, apply)
    return result


def _decision(run: dict[str, Any]) -> str | None:
    outcome = run.get("humanOutcome") or {}
    decision = outcome.get("decision")
    return str(decision).lower() if decision else None


def run_calibration(
    *,
    api: OctoApiClient,
    tenant_id: str,
    run_key: str,
    models: dict[str, str],
    limit: int = 200,
) -> CalibrationResult:
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow=WORKFLOW,
        run_key=run_key,
        subject_type="tenant",
        subject_id=tenant_id,
        input={"limit": limit},
        models=models,
    )
    try:
        if replayed is not None:
            return CalibrationResult.model_validate(replayed)

        rows = api.list_agent_runs(tenant_id, limit=limit)
        runs = [
            r
            for r in (rows if isinstance(rows, list) else [])
            if r.get("workflow") != WORKFLOW
        ]

        stats: dict[str, WorkflowStats] = {}
        disagreements: list[Disagreement] = []
        eval_cases: list[EvalCase] = []

        for run in runs:
            workflow = run.get("workflow") or "unknown"
            bucket = stats.setdefault(
                workflow,
                WorkflowStats(
                    runs=0, decided=0, agreed=0, disagreed=0, agreement_rate=None
                ),
            )
            bucket.runs += 1
            decision = _decision(run)
            if decision is None:
                continue
            bucket.decided += 1

            shipped = run.get("status") == "completed"
            if decision in AGREED:
                bucket.agreed += 1
                expect_ship = shipped
            elif decision in DISAGREED:
                # A human override means the machine's direction was wrong.
                bucket.disagreed += 1
                expect_ship = not shipped
                disagreements.append(
                    Disagreement(
                        run_id=str(run.get("id")),
                        run_key=str(run.get("runKey")),
                        workflow=workflow,
                        subject=f"{run.get('subjectType')}:{run.get('subjectId')}",
                        status=str(run.get("status")),
                        decision=decision,
                    )
                )
            else:
                # Unrecognized vocabulary isn't a verdict on the artifact —
                # count it as decided but keep the machine's direction.
                expect_ship = shipped
            eval_cases.append(
                EvalCase(
                    case=f"feedback:{run.get('id')}",
                    workflow=workflow,
                    run_id=str(run.get("id")),
                    expect_ship=expect_ship,
                )
            )

        for bucket in stats.values():
            if bucket.decided:
                bucket.agreement_rate = round(bucket.agreed / bucket.decided, 4)

        suggestions = [
            (
                f"'{name}': humans disagreed on {b.disagreed}/{b.decided} decided runs "
                f"(agreement {b.agreement_rate}) — review its gate thresholds or prompts"
            )
            for name, b in stats.items()
            if b.decided >= MIN_DECIDED_FOR_SUGGESTION
            and b.agreement_rate is not None
            and b.agreement_rate < AGREEMENT_FLOOR
        ]

        decided = sum(b.decided for b in stats.values())
        result = CalibrationResult(
            tenant_id=tenant_id,
            status="completed",
            analyzed=len(runs),
            decided=decided,
            stats=stats,
            disagreements=disagreements,
            eval_cases=eval_cases,
            suggestions=suggestions,
            disagreements_total=len(disagreements),
            eval_cases_total=len(eval_cases),
        )
        result = _fit(result)
        api.finish_run(run_id, status="completed", output=result.model_dump())
        return result
    except Exception as e:
        finish_failed(api, run_id, e)
        raise
