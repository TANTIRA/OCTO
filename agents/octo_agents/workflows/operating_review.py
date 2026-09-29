"""Operating-partner review — portfolio-company operational trajectory (ADR-0005 F13).

The Operating Partner watches a portfolio company's operating metrics quarter
over quarter and judges the value-creation plan: which levers are on track,
which have stalled. This is an internal review (not LP-facing), but it is
still a **sealed narrator** — the company's period metrics arrive inline and
the drafter runs with no tools, so a number has nowhere to come from but the
supplied series. jev then gates the narrative on grounding, coverage of the
declared levers, and whether it stays analytical rather than prescriptive.
A refusal keeps the draft on agent_run. No portfolio-company PII in the
prompt beyond the entity name.
"""

import json
from typing import Any, Literal

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel

from ..api_client import OctoApiClient
from ..judge import ChoiceQuestion, JudgeClient, NoulQuestion
from .screening_dd import _record_run
from .warm_context import warm_prompt

REVIEW_PROMPT = """You are the OCTO operating partner reviewing a portfolio company's
operating trajectory. Using only the period metrics and the declared
value-creation levers supplied below, write a review that, for each lever,
states what the metrics show and whether it is on track, stalled or off
track. Cite only figures present in the supplied series — never invent a
number, a period or a lever. Close with the levers that most need attention.

Do not prescribe specific actions or make commitments — this is an analytical
review for the deal team, not an operating plan. Under 500 words."""

# Operating points — the review ships only when jev reads it as grounded in
# the supplied series, covering every declared lever, and analytical rather
# than prescriptive.
GROUNDED_THRESHOLD = 0.7
COVERAGE_THRESHOLD = 0.6
BLOCKING_STANCE = {"prescriptive"}


class ReviewVerdict(BaseModel):
    submit: bool
    grounded_probability: float
    coverage_probability: float
    stance_band: str | None
    judge_lineage: dict[str, Any]


class ReviewResult(BaseModel):
    company: str
    status: Literal["completed", "refused"]
    review: str = ""
    verdict: ReviewVerdict | None = None
    note: str | None = None


def judge_review(
    judge: JudgeClient,
    *,
    company: str,
    levers: list[str],
    metrics: dict[str, Any],
    review: str,
) -> ReviewVerdict:
    result = judge.decide(
        state={
            "company": company,
            "levers": levers,
            "metrics": metrics,
            "review": review,
        },
        questions={
            "grounded": NoulQuestion(
                instructions=(
                    "Is every figure and period cited in the review present "
                    "in the supplied metrics — nothing invented or imported "
                    "from outside?"
                )
            ),
            "coverage": NoulQuestion(
                instructions=(
                    "Does the review assess every declared value-creation "
                    "lever, skipping none?"
                )
            ),
            "stance": ChoiceQuestion(
                instructions="How does the review read?",
                criteria={
                    "analytical": "assesses the trajectory, flags what needs attention",
                    "prescriptive": "directs specific actions or makes commitments",
                },
            ),
        },
        session_id=f"operating-review:{company}",
        user="octo-agents",
    )
    grounded = result.answers["grounded"]["noul"]
    coverage = result.answers["coverage"]["noul"]
    stance = result.answers["stance"].get("choice")
    return ReviewVerdict(
        submit=(
            grounded >= GROUNDED_THRESHOLD
            and coverage >= COVERAGE_THRESHOLD
            and stance not in BLOCKING_STANCE
        ),
        grounded_probability=grounded,
        coverage_probability=coverage,
        stance_band=stance,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def run_operating_review(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    tenant_id: str,
    company: str,
    levers: list[str],
    metrics: dict[str, Any],
    run_key: str,
    models: dict[str, str],
) -> ReviewResult:
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="operating-review",
        run_key=run_key,
        subject_type="company",
        subject_id=company,
        input={"company": company, "levers": levers, "metrics": metrics},
        models=models,
    )
    if replayed is not None:
        return ReviewResult.model_validate(replayed)

    try:
        cleaned = [lever for lever in levers if str(lever).strip()]
        if not cleaned or not metrics:
            result = ReviewResult(
                company=company,
                status="refused",
                note="no value-creation levers or no metrics to review",
            )
            api.finish_run(run_id, status="refused", output=result.model_dump())
            return result

        # The drafter gets no tools: the supplied series is the whole evidence
        # base, so a cited figure has nowhere else to come from. Warm context
        # (the fund's operating playbook) is allowed — it is the firm's own
        # standing brief, not another company's data.
        levers_text = "\n".join(f"- {lever}" for lever in cleaned)
        message = agent_model.invoke(
            [
                SystemMessage(content=warm_prompt(api, tenant_id, REVIEW_PROMPT)),
                HumanMessage(
                    content=(
                        f"Company: {company}\n\n"
                        f"Value-creation levers:\n{levers_text}\n\n"
                        f"Period metrics (the only evidence base):\n"
                        f"{json.dumps(metrics, default=str)}"
                    )
                ),
            ]
        )
        review = (
            message.content
            if isinstance(message.content, str)
            else str(message.content)
        )

        verdict = judge_review(
            judge, company=company, levers=cleaned, metrics=metrics, review=review
        )
        result = ReviewResult(
            company=company,
            status="completed" if verdict.submit else "refused",
            review=review,
            verdict=verdict,
            note=None if verdict.submit else "the jev gate refused the review",
        )
        api.finish_run(
            run_id,
            status=result.status,
            output=result.model_dump(),
            verdict={
                "grounded": {"noul": verdict.grounded_probability},
                "coverage": {"noul": verdict.coverage_probability},
                "stance": {"choice": verdict.stance_band},
                "lineage": verdict.judge_lineage,
            },
        )
        return result
    except Exception as e:
        api.finish_run(run_id, status="failed", error=str(e)[:2000])
        raise
