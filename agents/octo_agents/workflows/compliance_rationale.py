"""Compliance rationale — narrates deterministic evaluation outcomes (F9).

The platform's compliance engine has already run: rule, measured values,
result and the breach task all exist before this workflow is invoked. The
drafter's only job is to turn those outcomes into an approver-readable
rationale — it gets no tools, so every number in the prose traces to the
engine's output. Jev's citation gate verifies exactly that before the
rationale ships.
"""

import json
from typing import Any, Literal

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel

from ..api_client import OctoApiClient
from ..judge import JudgeClient, NoulQuestion
from .screening_dd import _record_run, finish_failed

RATIONALE_PROMPT = """You are the OCTO compliance analyst. Turn the supplied rule
evaluations into a rationale an approver can act on. For each outcome state
the rule, what was measured, the result, and — for a breach — what the review
task should weigh. Quote the rule ids and measured values exactly as given;
never restate a threshold differently or cite a figure that is not there.
Concise prose, one short paragraph per outcome."""

# Operating points — the rationale ships only when every citation is exact and
# every outcome is addressed.
CITED_THRESHOLD = 0.7
COMPLETE_THRESHOLD = 0.6


class RationaleVerdict(BaseModel):
    ship: bool
    cited_probability: float
    complete_probability: float
    judge_lineage: dict[str, Any]


class RationaleResult(BaseModel):
    subject: str
    status: Literal["completed", "refused"]
    rationale: str = ""
    verdict: RationaleVerdict | None = None
    note: str | None = None


def judge_rationale(
    judge: JudgeClient,
    *,
    subject: str,
    outcomes: list[dict[str, Any]],
    rationale: str,
) -> RationaleVerdict:
    result = judge.decide(
        state={"subject": subject, "outcomes": outcomes, "rationale": rationale},
        questions={
            "cited": NoulQuestion(
                instructions=(
                    "Does every rule id, threshold, measured value and result "
                    "in the rationale match the supplied outcomes exactly — "
                    "nothing restated differently or invented?"
                )
            ),
            "complete": NoulQuestion(
                instructions="Does the rationale address every supplied outcome, skipping none?"
            ),
        },
        session_id=f"compliance:{subject}",
        user="octo-agents",
    )
    cited = result.require_noul("cited")
    complete = result.require_noul("complete")
    return RationaleVerdict(
        ship=cited >= CITED_THRESHOLD and complete >= COMPLETE_THRESHOLD,
        cited_probability=cited,
        complete_probability=complete,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def run_compliance_rationale(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    tenant_id: str,
    subject: str,
    as_of: str,
    outcomes: list[dict[str, Any]],
    run_key: str,
    models: dict[str, str],
) -> RationaleResult:
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="compliance-rationale",
        run_key=run_key,
        subject_type="compliance",
        subject_id=f"{subject}/{as_of}",
        input={"subject": subject, "as_of": as_of, "outcomes": outcomes},
        models=models,
    )
    try:
        if replayed is not None:
            return RationaleResult.model_validate(replayed)
        if not outcomes:
            result = RationaleResult(
                subject=subject,
                status="refused",
                note="no rule outcomes to narrate",
            )
            api.finish_run(run_id, status="refused", output=result.model_dump())
            return result

        # The drafter gets no tools: the engine's outcomes are the whole
        # evidence base — a citation has nowhere else to come from.
        message = agent_model.invoke(
            [
                SystemMessage(content=RATIONALE_PROMPT),
                HumanMessage(
                    content=(
                        f"Narrate the compliance outcomes for {subject} as of {as_of}:\n"
                        f"{json.dumps(outcomes, default=str)}"
                    )
                ),
            ]
        )
        rationale = (
            message.content
            if isinstance(message.content, str)
            else str(message.content)
        )

        verdict = judge_rationale(
            judge, subject=subject, outcomes=outcomes, rationale=rationale
        )
        result = RationaleResult(
            subject=subject,
            status="completed" if verdict.ship else "refused",
            rationale=rationale,
            verdict=verdict,
            note=None
            if verdict.ship
            else "the jev gate could not verify the rationale's citations",
        )
        api.finish_run(
            run_id,
            status=result.status,
            output=result.model_dump(),
            verdict={
                "cited": {"noul": verdict.cited_probability},
                "complete": {"noul": verdict.complete_probability},
                "lineage": verdict.judge_lineage,
            },
        )
        return result
    except Exception as e:
        finish_failed(api, run_id, e)
        raise
