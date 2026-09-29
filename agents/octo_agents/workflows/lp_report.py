"""LP report drafting — the drafter narrates the report-job's own facts (F8).

The job row carries the quantitative input inline (the same inline-series /
inline-events contract the engines use), so the drafter runs with no tools at
all: it narrates the supplied facts and nothing else — there is no surface
through which another tenant's data could enter the letter. Jev's gate then
verifies the draft stays supported by that input before Kotlin will mark the
job done. Release to an LP still waits on the job's approval task, exactly
like every engine-produced artifact.
"""

import json
from typing import Any, Literal

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel

from ..api_client import OctoApiClient
from ..judge import ChoiceQuestion, JudgeClient, NoulQuestion
from .screening_dd import _record_run
from .warm_context import warm_prompt

LP_PROMPT = """You are the OCTO investor-relations writer. Draft the quarterly LP letter
for the fund whose facts follow. Every number, date and commitment you cite
must come from those facts — if a figure is not there, do not cite it. No LP
names, no personal data, no other funds' figures.

Structure:
1. Performance — the reported measures in plain language
2. Portfolio — what the position and flows say about the period
3. Outlook — measured forward commentary, no promises

Under 400 words."""

# The gate's operating points — a letter ships only when jev reads it as
# complete and every claim supported by the supplied facts.
COMPLETE_THRESHOLD = 0.6
SUPPORTED_THRESHOLD = 0.7


class LpVerdict(BaseModel):
    submit: bool
    complete_probability: float
    supported_probability: float
    tone_band: str | None
    judge_lineage: dict[str, Any]


class LpReportResult(BaseModel):
    job_id: str
    status: Literal["completed", "refused"]
    memo: str = ""
    verdict: LpVerdict | None = None
    stage_note: str | None = None


def judge_letter(
    judge: JudgeClient,
    *,
    job_id: str,
    facts: dict[str, Any],
    memo: str,
) -> LpVerdict:
    result = judge.decide(
        state={"job_id": job_id, "facts": facts, "memo": memo},
        questions={
            "complete": NoulQuestion(
                instructions=(
                    "Does the letter cover performance, portfolio and outlook "
                    "with real content, not placeholders?"
                )
            ),
            "supported": NoulQuestion(
                instructions=(
                    "Is every figure, date and commitment the letter cites "
                    "present in the supplied facts — nothing invented or "
                    "imported from outside?"
                )
            ),
            "tone": ChoiceQuestion(
                instructions="Which register does the letter read as?",
                criteria={
                    "lp-ready": "measured, factual, suitable for limited partners",
                    "internal": "draft-quality, speculative, or over-promising",
                },
            ),
        },
        session_id=f"lp-report:{job_id}",
        user="octo-agents",
    )
    complete = result.answers["complete"]["noul"]
    supported = result.answers["supported"]["noul"]
    tone = result.answers["tone"].get("choice")
    return LpVerdict(
        submit=(
            complete >= COMPLETE_THRESHOLD
            and supported >= SUPPORTED_THRESHOLD
            and tone != "internal"
        ),
        complete_probability=complete,
        supported_probability=supported,
        tone_band=tone,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def run_lp_report(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    job_id: str,
    tenant_id: str,
    run_key: str,
    position_source_type: str,
    position_source_id: str,
    measures: list[str],
    parameters: dict[str, Any],
    models: dict[str, str],
) -> LpReportResult:
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="lp-report",
        run_key=run_key,
        subject_type="report-job",
        subject_id=job_id,
        input={
            "position_source_type": position_source_type,
            "position_source_id": position_source_id,
            "measures": measures,
            "parameters": parameters,
        },
        models=models,
    )
    if replayed is not None:
        return LpReportResult.model_validate(replayed)

    try:
        facts = {
            "position_source": f"{position_source_type}:{position_source_id}",
            "measures": measures,
            "parameters": parameters,
        }
        # The drafter gets no tools: the job's own parameters are the whole
        # evidence base, so a boundary breach is impossible by construction.
        message = agent_model.invoke(
            [
                SystemMessage(content=warm_prompt(api, tenant_id, LP_PROMPT)),
                HumanMessage(
                    content=(
                        "Draft the LP letter from these facts only:\n"
                        f"{json.dumps(facts, default=str)}"
                    )
                ),
            ]
        )
        memo = message.content if isinstance(message.content, str) else str(message.content)

        verdict = judge_letter(judge, job_id=job_id, facts=facts, memo=memo)
        result = LpReportResult(
            job_id=job_id,
            status="completed" if verdict.submit else "refused",
            memo=memo,
            verdict=verdict,
            stage_note=None if verdict.submit else "the jev gate refused the draft",
        )
        api.finish_run(
            run_id,
            status=result.status,
            output=result.model_dump(),
            verdict={
                "complete": {"noul": verdict.complete_probability},
                "supported": {"noul": verdict.supported_probability},
                "tone": {"choice": verdict.tone_band},
                "lineage": verdict.judge_lineage,
            },
        )
        return result
    except Exception as e:
        api.finish_run(run_id, status="failed", error=str(e)[:2000])
        raise
