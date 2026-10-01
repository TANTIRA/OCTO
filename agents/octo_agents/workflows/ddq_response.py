"""DDQ / RFP response drafting — the Fundraising persona (ADR-0005 F10).

An LP sends a due-diligence questionnaire; the firm answers it from its own
materials. This is LP-facing output, so it follows the sealed-narrator
pattern (like lp-report and compliance-rationale): the firm facts arrive
inline, the drafter runs with **no tools**, and it answers only from those
facts — a question the materials do not cover is answered honestly as "not
in the provided materials", never fabricated. Jev then gates the draft on
grounding, coverage and tone before it can ship. No LP identities enter the
prompt; release to the LP stays a human step outside this workflow.
"""

import json
from typing import Any, Literal

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel

from ..api_client import OctoApiClient
from ..deadline import invoke_within_deadline
from ..judge import ChoiceQuestion, JudgeClient, NoulQuestion
from .screening_dd import _record_run, finish_failed

DDQ_PROMPT = """You are the OCTO fundraising analyst answering a limited partner's
due-diligence questionnaire. Answer each numbered question using only the firm
facts supplied below — never invent a figure, date, policy or track-record
claim. If the materials do not cover a question, answer exactly: "Not covered
in the provided materials." Do not guess, and do not pad.

Keep each answer to a few sentences. No LP names, no personal data, no other
clients' figures. Return the answers numbered to match the questions."""

# Operating points — the response ships only when jev reads every answer as
# grounded in the supplied facts, every question addressed, and the tone
# fit for a limited partner.
GROUNDED_THRESHOLD = 0.7
ANSWERED_THRESHOLD = 0.6


class DdqVerdict(BaseModel):
    submit: bool
    grounded_probability: float
    answered_probability: float
    tone_band: str | None
    judge_lineage: dict[str, Any]


class DdqResult(BaseModel):
    subject: str
    status: Literal["completed", "refused"]
    response: str = ""
    verdict: DdqVerdict | None = None
    note: str | None = None


def judge_ddq(
    judge: JudgeClient,
    *,
    subject: str,
    questions: list[str],
    facts: dict[str, Any],
    response: str,
) -> DdqVerdict:
    result = judge.decide(
        state={
            "subject": subject,
            "questions": questions,
            "facts": facts,
            "response": response,
        },
        questions={
            "grounded": NoulQuestion(
                instructions=(
                    "Is every figure, date, policy and claim in the answers "
                    "present in the supplied firm facts — nothing invented or "
                    "imported from outside? An honest 'not covered' counts as "
                    "grounded."
                )
            ),
            "answered": NoulQuestion(
                instructions=(
                    "Does the response address every supplied question, "
                    "skipping none — either answering it or stating the "
                    "materials do not cover it?"
                )
            ),
            "tone": ChoiceQuestion(
                instructions="Which register does the response read as?",
                criteria={
                    "lp-ready": "measured, factual, suitable for a limited partner",
                    "internal": "draft-quality, speculative, or over-claiming",
                },
            ),
        },
        session_id=f"ddq-response:{subject}",
        user="octo-agents",
    )
    grounded = result.require_noul("grounded")
    answered = result.require_noul("answered")
    tone = result.require_choice("tone")
    return DdqVerdict(
        submit=(
            grounded >= GROUNDED_THRESHOLD
            and answered >= ANSWERED_THRESHOLD
            and tone != "internal"
        ),
        grounded_probability=grounded,
        answered_probability=answered,
        tone_band=tone,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def run_ddq_response(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    tenant_id: str,
    subject: str,
    questions: list[str],
    facts: dict[str, Any],
    run_key: str,
    models: dict[str, str],
) -> DdqResult:
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="ddq-response",
        run_key=run_key,
        subject_type="ddq",
        subject_id=subject,
        input={"subject": subject, "questions": questions, "facts": facts},
        models=models,
    )
    try:
        if replayed is not None:
            return DdqResult.model_validate(replayed)
        cleaned = [q for q in questions if str(q).strip()]
        if not cleaned:
            result = DdqResult(
                subject=subject,
                status="refused",
                note="no questions to answer",
            )
            api.finish_run(run_id, status="refused", output=result.model_dump())
            return result

        # The drafter gets no tools: the supplied firm facts are the whole
        # evidence base, so an answer has nowhere else to draw from — a
        # boundary breach is impossible by construction.
        numbered = "\n".join(f"{i + 1}. {q}" for i, q in enumerate(cleaned))
        message = invoke_within_deadline(
            agent_model,
            [
                SystemMessage(content=DDQ_PROMPT),
                HumanMessage(
                    content=(
                        f"Firm facts (the only evidence base):\n"
                        f"{json.dumps(facts, default=str)}\n\n"
                        f"Questions:\n{numbered}"
                    )
                ),
            ]
        )
        response = (
            message.content
            if isinstance(message.content, str)
            else str(message.content)
        )

        verdict = judge_ddq(
            judge, subject=subject, questions=cleaned, facts=facts, response=response
        )
        result = DdqResult(
            subject=subject,
            status="completed" if verdict.submit else "refused",
            response=response,
            verdict=verdict,
            note=None if verdict.submit else "the jev gate refused the response",
        )
        api.finish_run(
            run_id,
            status=result.status,
            output=result.model_dump(),
            verdict={
                "grounded": {"noul": verdict.grounded_probability},
                "answered": {"noul": verdict.answered_probability},
                "tone": {"choice": verdict.tone_band},
                "lineage": verdict.judge_lineage,
            },
        )
        return result
    except Exception as e:
        finish_failed(api, run_id, e)
        raise
