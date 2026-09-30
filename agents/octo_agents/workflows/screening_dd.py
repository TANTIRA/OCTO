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

import json
import logging
from typing import Any, Literal

from deepagents import create_deep_agent
from pydantic import BaseModel

from ..api_client import OctoApiClient, OctoApiError
from ..judge import ChoiceQuestion, JudgeClient, NoulQuestion, ScoreQuestion
from ..tools import read_tools
from .warm_context import warm_prompt

_log = logging.getLogger("octo_agents.workflows")

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
    stage_note: str | None = None


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
    probability = result.require_noul("sufficient")
    return PreflightVerdict(
        record_sufficient=probability >= PREFLIGHT_THRESHOLD,
        probability=probability,
        gap_band=result.require_choice("gap"),
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
            criteria=[
                "1-unrelated",
                "2-tangential",
                "3-useful",
                "4-relevant",
                "5-decisive",
            ],
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
        ((i, result.require_score(f"relevance_{i}")) for i in range(len(scored))),
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
            criteria=[
                "1-unsupported",
                "2-thin",
                "3-adequate",
                "4-strong",
                "5-exemplary",
            ],
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
    advance = verdict.require("advance")
    rationale = verdict.require("rationale")
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


class SubjectNotInTenantError(LookupError):
    """The requested subject is not visible in, or does not belong to, the
    requested tenant. Acting on it would book one tenant's run (and mediated
    writes) against another tenant's record (backlog #318)."""

    def __init__(self, subject: str, tenant_id: str) -> None:
        super().__init__(f"{subject} does not belong to tenant {tenant_id}")


def load_prospect_in_tenant(api: OctoApiClient, prospect_id: str, tenant_id: str) -> Any:
    """Fetches the prospect and verifies it belongs to `tenant_id` — the body's
    tenant and prospect are independent caller inputs, and the platform's
    record is the only source of truth for which tenant owns the prospect.
    Must run before `_record_run` so a mismatched pair never books a run."""
    try:
        prospect = api.get_prospect(prospect_id)
    except OctoApiError as e:
        # The platform answers 404 for a prospect outside the principal's tenants.
        if e.status_code == 404:
            raise SubjectNotInTenantError(f"prospect {prospect_id}", tenant_id) from e
        raise
    owner = prospect.get("tenantId") if isinstance(prospect, dict) else None
    if owner is None or str(owner).lower() != tenant_id.lower():
        raise SubjectNotInTenantError(f"prospect {prospect_id}", tenant_id)
    return prospect


class RunKeyCollisionError(RuntimeError):
    """A run_key resolved to an existing run for a different workflow, subject
    or input. Returning that run's output would hand back another request's
    result (e.g. the wrong prospect's memo), so the replay is refused instead
    (backlog #318)."""

    def __init__(self, run_key: str, expected: str, found: str) -> None:
        super().__init__(
            f"run_key {run_key!r} already bound to {found}, not {expected} — refusing replay"
        )
        self.run_key = run_key


def _record_run(
    api: OctoApiClient,
    *,
    tenant_id: str,
    workflow: str,
    run_key: str,
    subject_type: str,
    subject_id: str,
    input: Any,
    models: dict[str, str],
) -> tuple[str, Any | None]:
    """Opens the agent_run row for this invocation. Returns (run_id, None) for a
    fresh run, or (run_id, replayed_output) when run_key already closed — a retried
    trigger reads its own result back instead of running twice.

    The platform dedupes run_key within a tenant. Before trusting a replayed
    row, this verifies its recorded subject matches the one requested: a
    caller-minted run_key that collides with a different subject must not read
    back the wrong subject's output (backlog #318). A fresh CREATE returns only
    an id, so the check applies only to a dedupe replay (which echoes the row).
    """
    recorded = api.record_run(
        tenant_id=tenant_id,
        workflow=workflow,
        run_key=run_key,
        subject_type=subject_type,
        subject_id=subject_id,
        input=input,
        models=models,
    )
    # A dedupe replay echoes the stored row (subjectType/subjectId present); a
    # fresh insert returns only {"id"}. Guard the replay against a subject
    # mismatch either way — cached output or an in-flight run under the key.
    replay_subject_type = recorded.get("subjectType")
    if replay_subject_type is not None:
        found = f"{recorded.get('workflow', workflow)}:{replay_subject_type}/{recorded.get('subjectId')}"
        expected = f"{workflow}:{subject_type}/{subject_id}"
        if found != expected:
            raise RunKeyCollisionError(run_key, expected, found)
        # Same subject, different request (e.g. another question or as_of under
        # a reused key): the stored output answers a different input. Compare
        # through a JSON round trip — the platform echoes input from jsonb.
        if "input" in recorded and recorded["input"] != json.loads(json.dumps(input)):
            raise RunKeyCollisionError(run_key, f"{expected} (this input)", f"{found} (another input)")
    if recorded.get("status") and recorded["status"] != "running" and recorded.get("output"):
        return recorded["id"], recorded["output"]
    return recorded["id"], None


def finish_failed(api: OctoApiClient, run_id: str, error: Exception) -> None:
    """Mark a crashed run `failed` for F4's audit spine — without letting the
    bookkeeping call mask the original error (backlog #15). If `finish_run`
    itself throws (edge down, the very failure that crashed the run), the
    original exception still propagates from the caller's bare `raise`; this
    swallows and logs the secondary failure rather than replacing the real one.
    """
    try:
        api.finish_run(run_id, status="failed", error=str(error)[:2000])
    except Exception as bookkeeping:  # noqa: BLE001 - must not mask `error`
        _log.warning(
            "finish_run(failed) for run %s could not land: %s", run_id, bookkeeping
        )


def run_screening_dd(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    prospect_id: str,
    tenant_id: str,
    run_key: str,
    models: dict[str, str],
) -> ScreeningResult:
    prospect_state = load_prospect_in_tenant(api, prospect_id, tenant_id)
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="screening-dd",
        run_key=run_key,
        subject_type="prospect",
        subject_id=prospect_id,
        input={"prospect_id": prospect_id},
        models=models,
    )
    try:
        # Replay validation lives inside the guard: a stored output that no
        # longer validates is a corrupt record — mark the run failed instead of
        # serving it or crashing without bookkeeping.
        if replayed is not None:
            return ScreeningResult.model_validate(replayed)

        raw_events = api.list_prospect_events(prospect_id)
        events = raw_events if isinstance(raw_events, list) else []

        preflight = preflight_gate(
            judge, prospect_id=prospect_id, prospect_state=prospect_state, events=events
        )
        if not preflight.record_sufficient:
            result = ScreeningResult(
                prospect_id=prospect_id,
                status="refused",
                preflight=preflight,
            )
            api.finish_run(
                run_id,
                status="refused",
                output=result.model_dump(),
                verdict={"sufficient": {"noul": preflight.probability}},
            )
            return result

        admitted, retrieval = score_events(
            judge, prospect_id=prospect_id, prospect_state=prospect_state, events=events
        )

        agent = create_deep_agent(
            model=agent_model,
            tools=read_tools(api),
            system_prompt=warm_prompt(api, tenant_id, SCREENING_PROMPT),
        )
        evidence = (
            "\n".join(f"- {_digest(e)}" for e in admitted) or "- (no events on record)"
        )
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
        stage_note = None
        if verdict.proceed:
            try:
                screening_response = api.request_screening(prospect_id)
            except OctoApiError as e:
                if e.status_code != 409:
                    raise
                # Task already open or the stage moved on — same degrade as
                # ic_memo: the memo still lands as a judged draft.
                stage_note = "screening task already open — memo left as judged draft"

        result = ScreeningResult(
            prospect_id=prospect_id,
            status="completed",
            memo=memo,
            preflight=preflight,
            retrieval=retrieval,
            verdict=verdict,
            screening_requested=verdict.proceed,
            screening_response=screening_response,
            stage_note=stage_note,
        )
        api.finish_run(
            run_id,
            status="completed",
            output=result.model_dump(),
            verdict={
                "advance": {"noul": verdict.proceed_probability},
                "rationale": {"choice": verdict.rationale_band},
                "lineage": verdict.judge_lineage,
            },
        )
        return result
    except Exception as e:
        # The run's bookkeeping must not hide its failure — a crashed run lands
        # `failed` with the error text so F4 sees it, then the error propagates.
        finish_failed(api, run_id, e)
        raise
