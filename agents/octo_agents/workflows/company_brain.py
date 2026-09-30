"""Company-brain query — natural-language questions over tenant data (F7).

Flow:
  1. jev pre-gates the question: is it answerable from what this platform
     holds (prospect pipeline, event history, asset records), and which lane
     does it touch? Unanswerable or out-of-scope questions stop at ~$0.0004 —
     no drafter spend, no tools fired.
  2. The drafter answers with read tools — get_prospect / list_prospect_events /
     get_asset plus list_pipeline so a question can fan out over a stage —
     citing the ids it used.
  3. jev post-gates: does the answer answer the question, and is every claim
     supported by what the tools returned? A draft that fails stays a refusal —
     the caller sees an honest "not enough evidence", never a confident
     hallucination.
"""

from typing import Any, Literal

from deepagents import create_deep_agent
from langchain_core.tools import tool
from pydantic import BaseModel

from ..api_client import OctoApiClient
from ..judge import ChoiceQuestion, JudgeClient, NoulQuestion
from ..tools import read_tools
from .screening_dd import _record_run, extract_final_text, finish_failed
from .warm_context import warm_prompt

BRAIN_PROMPT = """You are the OCTO company brain. Answer the analyst's question from the
platform's records using the tools — prospect states and event histories,
assets, and list_pipeline(stage) to enumerate the deal flow (stages: sourced,
screening, due-diligence, ic-review, invested, passed).

Rules: cite the prospect/asset id behind every claim. If the records cannot
answer, say what is missing instead of inventing. Concise prose, no preamble."""

# Operating points — a question must look answerable before a drafter call,
# and the draft must be both responsive and supported before it ships.
ANSWERABLE_THRESHOLD = 0.5
SUPPORTED_THRESHOLD = 0.7

STAGES = ["sourced", "screening", "due-diligence", "ic-review", "invested", "passed"]


class BrainVerdict(BaseModel):
    ship: bool
    answers_probability: float
    supported_probability: float
    judge_lineage: dict[str, Any]


class BrainResult(BaseModel):
    tenant_id: str
    status: Literal["completed", "refused"]
    answer: str = ""
    verdict: BrainVerdict | None = None
    note: str | None = None


def _pipeline_tool(api: OctoApiClient, tenant_id: str) -> Any:
    @tool
    def list_pipeline(stage: str) -> str:
        """List prospects standing at a pipeline stage — one of: sourced,
        screening, due-diligence, ic-review, invested, passed."""
        if stage not in STAGES:
            return f"unknown stage {stage!r}; stages: {', '.join(STAGES)}"
        return str(api.list_pipeline(tenant_id, stage))

    return list_pipeline


def _answerable(
    judge: JudgeClient,
    *,
    tenant_id: str,
    question: str,
) -> tuple[bool, float, dict[str, Any]]:
    result = judge.decide(
        state={"question": question},
        questions={
            "answerable": NoulQuestion(
                instructions=(
                    "Could a private-equity deal-flow platform's records — "
                    "prospect pipeline, event history, asset master — plausibly "
                    "contain what this question asks for?"
                )
            ),
            "lane": ChoiceQuestion(
                instructions="Which evidence lane does the question mostly touch?",
                criteria={
                    "pipeline": "prospects, stages, screening, diligence",
                    "assets": "asset master records and identifiers",
                    "out-of-scope": "nothing this platform holds",
                },
            ),
        },
        session_id=f"brain:{tenant_id}",
        user="octo-agents",
    )
    p = result.answers["answerable"]["noul"]
    return (
        p >= ANSWERABLE_THRESHOLD,
        p,
        {
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
            "lane": result.answers["lane"].get("choice"),
        },
    )


def judge_answer(
    judge: JudgeClient,
    *,
    tenant_id: str,
    question: str,
    answer: str,
) -> BrainVerdict:
    result = judge.decide(
        state={"question": question, "answer": answer},
        questions={
            "answers": NoulQuestion(
                instructions="Does the answer actually answer the question that was asked?"
            ),
            "supported": NoulQuestion(
                instructions=(
                    "Is every claim in the answer supported by retrieved records "
                    "and cited to an id — nothing invented?"
                )
            ),
        },
        session_id=f"brain-answer:{tenant_id}",
        user="octo-agents",
    )
    answers_p = result.answers["answers"]["noul"]
    supported_p = result.answers["supported"]["noul"]
    return BrainVerdict(
        ship=answers_p >= SUPPORTED_THRESHOLD and supported_p >= SUPPORTED_THRESHOLD,
        answers_probability=answers_p,
        supported_probability=supported_p,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def run_company_brain(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    tenant_id: str,
    question: str,
    run_key: str,
    models: dict[str, str],
) -> BrainResult:
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="company-brain",
        run_key=run_key,
        subject_type="tenant",
        subject_id=tenant_id,
        input={"question": question},
        models=models,
    )
    if replayed is not None:
        return BrainResult.model_validate(replayed)

    try:
        ok, probability, lineage = _answerable(
            judge, tenant_id=tenant_id, question=question
        )
        if not ok:
            result = BrainResult(
                tenant_id=tenant_id,
                status="refused",
                note="question does not look answerable from the platform's records",
            )
            api.finish_run(
                run_id,
                status="refused",
                output=result.model_dump(),
                verdict={"answerable": {"noul": probability}, "lineage": lineage},
            )
            return result

        agent = create_deep_agent(
            model=agent_model,
            tools=[*read_tools(api), _pipeline_tool(api, tenant_id)],
            system_prompt=warm_prompt(api, tenant_id, BRAIN_PROMPT),
        )
        invoked = agent.invoke({"messages": [("user", question)]})
        answer = extract_final_text(invoked)

        verdict = judge_answer(
            judge, tenant_id=tenant_id, question=question, answer=answer
        )
        result = BrainResult(
            tenant_id=tenant_id,
            status="completed" if verdict.ship else "refused",
            answer=answer,
            verdict=verdict,
            note=None
            if verdict.ship
            else "the jev gate could not support the draft answer",
        )
        api.finish_run(
            run_id,
            status=result.status,
            output=result.model_dump(),
            verdict={
                "answers": {"noul": verdict.answers_probability},
                "supported": {"noul": verdict.supported_probability},
                "lineage": verdict.judge_lineage,
            },
        )
        return result
    except Exception as e:
        finish_failed(api, run_id, e)
        raise
