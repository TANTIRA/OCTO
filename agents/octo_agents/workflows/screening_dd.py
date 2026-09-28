"""Investment Screening & DD — the first ADR-0005 workflow (tasks-only).

Flow, all transitions in code rather than model discretion:
  1. The drafter (deepseek) runs a deepagent with READ-ONLY tools and produces
     a screening memo. It is given no way to write — the tool surface it sees
     has no write path at all.
  2. The judge (jev, decisions API) answers typed questions about the memo with
     calibrated probabilities — it scores, it never drafts.
  3. Workflow code compares the calibrated verdict to the threshold and opens a
     screening task via POST /prospects/{id}/screen. A human still decides the
     task inside the platform — segregation of duties end to end.
"""

from typing import Any

from deepagents import create_deep_agent
from pydantic import BaseModel

from ..api_client import OctoApiClient
from ..judge import ChoiceQuestion, JudgeClient, NoulQuestion, ScoreQuestion
from ..tools import read_tools

SCREENING_PROMPT = """You are the OCTO investment screening analyst. Draft a screening memo
for the prospect named in the task using only facts you pulled through the
tools — never invent financials, dates or counterparties.

Structure the memo as:
1. Summary — what the prospect is and why it is in the pipeline now
2. Evidence — what the prospect events and asset records actually show
3. Gaps — what a DD checklist still needs that the record does not contain
4. Recommendation — advance / hold / reject with the single strongest reason

Keep it under 400 words. Do not include LP identities or personal data."""


class ScreeningVerdict(BaseModel):
    proceed: bool
    proceed_probability: float
    confidence: float
    rationale_band: str | None
    judge_lineage: dict[str, Any]


class ScreeningResult(BaseModel):
    prospect_id: str
    memo: str
    verdict: ScreeningVerdict
    screening_requested: bool
    screening_response: Any = None


# Calibrated threshold for opening a human review task. Chosen conservatively:
# the judge must be at least this sure the memo supports advancement.
PROCEED_THRESHOLD = 0.7


def extract_final_text(result: dict[str, Any]) -> str:
    for message in reversed(result.get("messages", [])):
        content = getattr(message, "content", None)
        if isinstance(content, str) and content.strip():
            return content
    raise ValueError("deepagent returned no memo text")


def judge_memo(
    judge: JudgeClient,
    *,
    prospect_id: str,
    prospect_state: Any,
    memo: str,
) -> ScreeningVerdict:
    questions: dict[str, Any] = {
        "advance": NoulQuestion(
            instructions=(
                "Given this prospect state and screening memo, should OCTO open a "
                "screening task for a human analyst to advance this prospect?"
            )
        ),
        "rationale": ChoiceQuestion(
            instructions="What is the single strongest driver of the decision?",
            criteria={
                "evidence": "the record supports advancement on its merits",
                "gaps": "the record is too thin to decide — DD needed first",
                "risk": "something in the record argues against advancing",
            },
        ),
        "quality": ScoreQuestion(
            instructions="Rate the memo's evidentiary quality for screening purposes.",
            criteria=["1-unsupported", "2-thin", "3-adequate", "4-strong", "5-exemplary"],
        ),
    }
    verdict = judge.decide(
        state={"prospect_id": prospect_id, "prospect": prospect_state, "memo": memo},
        questions=questions,
        session_id=f"screening-dd:{prospect_id}",
        user="octo-agents",
    )
    advance = verdict.answers["advance"]
    rationale = verdict.answers["rationale"]
    return ScreeningVerdict(
        proceed=advance["noul"] >= PROCEED_THRESHOLD,
        proceed_probability=advance["noul"],
        confidence=rationale.get("confidence", 0.0),
        rationale_band=rationale.get("choice"),
        judge_lineage={
            "model": verdict.model,
            "provider": verdict.provider,
            "request_id": verdict.id,
        },
    )


def run_screening_dd(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    prospect_id: str,
) -> ScreeningResult:
    agent = create_deep_agent(
        model=agent_model,
        tools=read_tools(api),
        system_prompt=SCREENING_PROMPT,
    )
    result = agent.invoke(
        {
            "messages": [
                (
                    "user",
                    f"Draft the screening memo for prospect {prospect_id}. "
                    "Pull its state and event history through the tools first.",
                )
            ]
        }
    )
    memo = extract_final_text(result)

    prospect_state = api.get_prospect(prospect_id)
    verdict = judge_memo(
        judge, prospect_id=prospect_id, prospect_state=prospect_state, memo=memo
    )

    screening_response = None
    if verdict.proceed:
        screening_response = api.request_screening(prospect_id)

    return ScreeningResult(
        prospect_id=prospect_id,
        memo=memo,
        verdict=verdict,
        screening_requested=verdict.proceed,
        screening_response=screening_response,
    )
