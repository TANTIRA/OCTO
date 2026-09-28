"""Investment Screening & DD — the first ADR-0005 workflow (tasks-only).

Flow, all transitions in code rather than model discretion:
  1. PRE-FLIGHT (F1) — jev decides whether the record carries enough real signal
     to justify a drafter call at all. ~$0.0004 vs the model run it gates.
  2. RETRIEVAL (F2) — jev scores each prospect event for relevance to the
     screening task; only admitted chunks enter the drafter's context. The
     channel is bounded — send the signal, not the history.
  3. The drafter (deepseek) runs a deepagent with READ-ONLY tools and produces
     a screening memo over the admitted evidence. It has no write path.
  4. The judge answers typed questions about the memo with calibrated
     probabilities — it scores, it never drafts.
  5. Workflow code compares the calibrated verdict to the threshold and opens a
     screening task via POST /prospects/{id}/screen. A human still decides the
     task inside the platform — segregation of duties end to end.

A record the pre-flight refuses exits at step 2 with status=refused: no drafter
call, no write, and the refusal verdict is still recorded for F4's audit spine.
"""

from typing import Any, Literal

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

# Calibrated operating points. Chosen conservatively and recalibrated from the
# F12 feedback loop once production verdicts accumulate:
#   - the judge must be at least half-sure the record supports a memo before a
#     drafter call is worth spending (a thin record still drafts a gaps memo;
#     refusal is for records with no substance at all);
#   - an event chunk needs a mid-scale relevance score to enter the context;
#   - the memo verdict bar for opening a human review task stays at 0.7.
PREFLIGHT_THRESHOLD = 0.5
RELEVANCE_MIN_SCORE = 3
PROCEED_THRESHOLD = 0.7

# Bound the judge's state and the questions map — a prospect with a long
# history still pays one bounded decide() call.
MAX_SCORED_EVENTS = 20
EVENT_DIGEST_CHARS = 400


class PreflightVerdict(BaseModel):
    record_sufficient: bool
    probability: float
    gap_band: str | None
    threshold: float
    judge_lineage: dict[str, Any]


class RetrievalVerdict(BaseModel):
    events_total: int
    events_admitted: int
    min_score: int
    judge_lineage: dict[str, Any]


class ScreeningVerdict(BaseModel):
    proceed: bool
    proceed_probability: float
    confidence: float
    rationale_band: str | None
    judge_lineage: dict[str, Any]


class ScreeningResult(BaseModel):
    prospect_id: str
    status: Literal["completed", "refused"]
    memo: str = ""
    preflight: PreflightVerdict
    retrieval: RetrievalVerdict | None = None
    verdict: ScreeningVerdict | None = None
    screening_requested: bool = False
    screening_response: Any = None


def _digest(event: Any) -> str:
    return str(event)[:EVENT_DIGEST_CHARS]


def extract_final_text(result: dict[str, Any]) -> str:
    for message in reversed(result.get("messages", [])):
        content = getattr(message, "content", None)
        if isinstance(content, str) and content.strip():
            return content
    raise ValueError("deepagent returned no memo text")


def preflight_gate(
    judge: JudgeClient,
    *,
    prospect_id: str,
    prospect_state: Any,
    events: list[Any],
) -> PreflightVerdict:
    """F1 — jev decides whether the record is worth a drafter call. The state is
    deliberately compact: the prospect plus a digest of each event, not raw rows."""
    result = judge.decide(
        state={
            "prospect_id": prospect_id,
            "prospect": prospect_state,
            "event_count": len(events),
            "events": [_digest(e) for e in events],
        },
        questions={
            "sufficient": NoulQuestion(
                instructions=(
                    "Does this prospect record contain enough real information — "
                    "facts, events, source detail — for an analyst to write a "
                    "screening memo? Judge the record's substance, not its quality."
                )
            ),
            "gap": ChoiceQuestion(
                instructions="If the record is insufficient, what is missing?",
                criteria={
                    "empty": "no meaningful events or state beyond registration",
                    "thin": "placeholders or a single fact — nothing to reason over",
                    "none": "the record is sufficient",
                },
            ),
        },
        session_id=f"screening-dd:preflight:{prospect_id}",
        user="octo-agents",
    )
    probability = result.answers["sufficient"]["noul"]
    return PreflightVerdict(
        record_sufficient=probability >= PREFLIGHT_THRESHOLD,
        probability=probability,
        gap_band=result.answers["gap"].get("choice"),
        threshold=PREFLIGHT_THRESHOLD,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def score_events(
    judge: JudgeClient,
    *,
    prospect_id: str,
    prospect_state: Any,
    events: list[Any],
) -> tuple[list[Any], RetrievalVerdict]:
    """F2 — jev scores each event's relevance to the screening task in a single
    decide() call; only chunks at or above RELEVANCE_MIN_SCORE reach the drafter.
    If nothing clears the bar the single best-scored event is still admitted so
    the memo can honestly report a signal-free history."""
    scored = events[:MAX_SCORED_EVENTS]
    if not scored:
        return [], RetrievalVerdict(
            events_total=0,
            events_admitted=0,
            min_score=RELEVANCE_MIN_SCORE,
            judge_lineage={},
        )

    questions = {
        f"relevance_{i}": ScoreQuestion(
            instructions=(
                "How relevant is this prospect event to judging whether the "
                "prospect merits a human screening review?"
            ),
            criteria=["1-unrelated", "2-tangential", "3-useful", "4-relevant", "5-decisive"],
        )
        for i in range(len(scored))
    }
    result = judge.decide(
        state={
            "prospect_id": prospect_id,
            "prospect": prospect_state,
            "task": "investment screening memo",
            "events": {f"event_{i}": _digest(e) for i, e in enumerate(scored)},
        },
        questions=questions,
        session_id=f"screening-dd:retrieval:{prospect_id}",
        user="octo-agents",
    )

    ranked = sorted(
        ((i, result.answers[f"relevance_{i}"]["score"]) for i in range(len(scored))),
        key=lambda pair: pair[1],
        reverse=True,
    )
    admitted = [scored[i] for i, score in ranked if score >= RELEVANCE_MIN_SCORE]
    if not admitted:
        admitted = [scored[ranked[0][0]]]

    return admitted, RetrievalVerdict(
        events_total=len(events),
        events_admitted=len(admitted),
        min_score=RELEVANCE_MIN_SCORE,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def judge_memo(
    judge: JudgeClient,
    *,
    prospect_id: str,
    prospect_state: Any,
    events: list[Any],
    memo: str,
) -> ScreeningVerdict:
    questions: dict[str, Any] = {
        "advance": NoulQuestion(
            instructions=(
                "Given this prospect state, its relevant events and the screening "
                "memo, should OCTO open a screening task for a human analyst to "
                "advance this prospect?"
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
        state={
            "prospect_id": prospect_id,
            "prospect": prospect_state,
            "events": [_digest(e) for e in events],
            "memo": memo,
        },
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
    prospect_state = api.get_prospect(prospect_id)
    raw_events = api.list_prospect_events(prospect_id)
    events = raw_events if isinstance(raw_events, list) else []

    preflight = preflight_gate(
        judge, prospect_id=prospect_id, prospect_state=prospect_state, events=events
    )
    if not preflight.record_sufficient:
        return ScreeningResult(
            prospect_id=prospect_id,
            status="refused",
            preflight=preflight,
        )

    admitted, retrieval = score_events(
        judge, prospect_id=prospect_id, prospect_state=prospect_state, events=events
    )

    agent = create_deep_agent(
        model=agent_model,
        tools=read_tools(api),
        system_prompt=SCREENING_PROMPT,
    )
    evidence = "\n".join(f"- {_digest(e)}" for e in admitted) or "- (no events on record)"
    result = agent.invoke(
        {
            "messages": [
                (
                    "user",
                    (
                        f"Draft the screening memo for prospect {prospect_id}.\n\n"
                        "Relevant record excerpts, already scored for this task:\n"
                        f"{evidence}\n\n"
                        "Pull the prospect's full state through the tools for anything "
                        "the excerpts do not cover."
                    ),
                )
            ]
        }
    )
    memo = extract_final_text(result)

    verdict = judge_memo(
        judge,
        prospect_id=prospect_id,
        prospect_state=prospect_state,
        events=admitted,
        memo=memo,
    )

    screening_response = None
    if verdict.proceed:
        screening_response = api.request_screening(prospect_id)

    return ScreeningResult(
        prospect_id=prospect_id,
        status="completed",
        memo=memo,
        preflight=preflight,
        retrieval=retrieval,
        verdict=verdict,
        screening_requested=verdict.proceed,
        screening_response=screening_response,
    )
