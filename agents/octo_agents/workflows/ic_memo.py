"""IC memo drafting — the drafter writes, jev gates, humans decide (ADR-0005 F5).

Flow:
  1. F1/F2 again — the record must be worth a memo and only jev-admitted events
     reach the drafter's context.
  2. The drafter writes the memo against the fixed template.
  3. jev answers the submission gate: completeness (noul), thesis alignment
     (choice) and evidentiary quality (score) in one decide().
  4. Only a passing memo asks the platform for an IC review —
     POST /prospects/{id}/ic-review opens the APPROVAL task. If the prospect is
     not standing at ic-review the call 409s and the memo stays a judged draft:
     the model never advances the pipeline itself. The memo's durable home is
     the agent_run row (keyed by run_key); the approval task is the human gate.
"""

from typing import Any, Literal

from deepagents import create_deep_agent
from pydantic import BaseModel

from ..api_client import OctoApiClient, OctoApiError
from ..judge import ChoiceQuestion, JudgeClient, NoulQuestion, ScoreQuestion
from ..tools import read_tools
from .screening_dd import (
    RetrievalVerdict,
    _record_run,
    extract_final_text,
    finish_failed,
    preflight_gate,
    score_events,
)
from .warm_context import warm_prompt

IC_MEMO_PROMPT = """You are the OCTO investment-committee analyst. Draft an IC memo for the
prospect named in the task using only facts pulled through the tools — never
invent financials, dates, counterparties or commitments.

Structure:
1. Thesis fit — how the prospect maps to the fund's mandate
2. Evidence — what the record and admitted events actually show
3. DD findings — what due diligence surfaced and what it left open
4. Risks — the strongest arguments against
5. Recommendation — approve / condition / decline with the decisive reason

Under 600 words. No LP identities, no personal data."""

# Operating points for the submission gate — a memo must read complete to jev,
# carry adequate evidence, and not be off-thesis before a human review opens.
COMPLETE_THRESHOLD = 0.6
EVIDENCE_MIN_SCORE = 3
BLOCKING_THESIS = {"off-thesis"}


class MemoVerdict(BaseModel):
    submit: bool
    complete_probability: float
    thesis_band: str | None
    evidence_score: float
    judge_lineage: dict[str, Any]


class IcMemoResult(BaseModel):
    prospect_id: str
    status: Literal["completed", "refused"]
    memo: str = ""
    retrieval: RetrievalVerdict | None = None
    verdict: MemoVerdict | None = None
    ic_review_requested: bool = False
    ic_review_task_id: str | None = None
    # Set when the gate passed but the prospect is not at ic-review — the memo
    # is a judged draft and a human moves the pipeline.
    stage_note: str | None = None


def judge_memo_for_ic(
    judge: JudgeClient,
    *,
    prospect_id: str,
    prospect_state: Any,
    events: list[Any],
    memo: str,
) -> MemoVerdict:
    result = judge.decide(
        state={
            "prospect_id": prospect_id,
            "prospect": prospect_state,
            "events": [str(e)[:400] for e in events],
            "memo": memo,
        },
        questions={
            "complete": NoulQuestion(
                instructions=(
                    "Does this memo cover all five IC sections — thesis fit, "
                    "evidence, DD findings, risks, recommendation — with real "
                    "content, not placeholders?"
                )
            ),
            "thesis": ChoiceQuestion(
                instructions="Where does the memo's analysis land against the fund thesis?",
                criteria={
                    "aligned": "evidence supports the thesis",
                    "mixed": "partly aligned, open questions remain",
                    "off-thesis": "the analysis argues against the mandate",
                },
            ),
            "evidence": ScoreQuestion(
                instructions="Rate the memo's evidentiary support for an IC decision.",
                criteria=[
                    "1-unsupported",
                    "2-thin",
                    "3-adequate",
                    "4-strong",
                    "5-exemplary",
                ],
            ),
        },
        session_id=f"ic-memo:{prospect_id}",
        user="octo-agents",
    )
    complete = result.require_noul("complete")
    thesis = result.require_choice("thesis")
    evidence = result.require_score("evidence")
    return MemoVerdict(
        submit=(
            complete >= COMPLETE_THRESHOLD
            and evidence >= EVIDENCE_MIN_SCORE
            and thesis not in BLOCKING_THESIS
        ),
        complete_probability=complete,
        thesis_band=thesis,
        evidence_score=evidence,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def run_ic_memo(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    prospect_id: str,
    tenant_id: str,
    run_key: str,
    models: dict[str, str],
) -> IcMemoResult:
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="ic-memo",
        run_key=run_key,
        subject_type="prospect",
        subject_id=prospect_id,
        input={"prospect_id": prospect_id},
        models=models,
    )
    try:
        if replayed is not None:
            return IcMemoResult.model_validate(replayed)

        prospect_state = api.get_prospect(prospect_id)
        raw_events = api.list_prospect_events(prospect_id)
        events = raw_events if isinstance(raw_events, list) else []

        preflight = preflight_gate(
            judge, prospect_id=prospect_id, prospect_state=prospect_state, events=events
        )
        if not preflight.record_sufficient:
            result = IcMemoResult(prospect_id=prospect_id, status="refused")
            api.finish_run(
                run_id,
                status="refused",
                output=result.model_dump(),
                verdict={"sufficient": {"noul": preflight.probability}},
            )
            return result

        # The retrieval verdict is part of the run's lineage — it records how
        # much of the record jev let through, not just which chunks.
        admitted, retrieval = score_events(
            judge, prospect_id=prospect_id, prospect_state=prospect_state, events=events
        )

        agent = create_deep_agent(
            model=agent_model,
            tools=read_tools(api),
            system_prompt=warm_prompt(api, tenant_id, IC_MEMO_PROMPT),
        )
        evidence = (
            "\n".join(f"- {str(e)[:400]}" for e in admitted)
            or "- (no events on record)"
        )
        result = agent.invoke(
            {
                "messages": [
                    (
                        "user",
                        (
                            f"Draft the IC memo for prospect {prospect_id}.\n\n"
                            "Relevant record excerpts, already scored for this task:\n"
                            f"{evidence}\n\n"
                            "Pull the prospect's full state through the tools for "
                            "anything the excerpts do not cover."
                        ),
                    )
                ]
            }
        )
        memo = extract_final_text(result)

        verdict = judge_memo_for_ic(
            judge,
            prospect_id=prospect_id,
            prospect_state=prospect_state,
            events=admitted,
            memo=memo,
        )

        task_id = None
        stage_note = None
        review_requested = False
        if verdict.submit:
            try:
                review = api.request_ic_review(prospect_id)
                # The request succeeded — the task opened even if the response
                # carried no id (the platform's contract returns one today, but
                # 'asked and accepted' must not read as 'not asked').
                review_requested = True
                task_id = review.get("taskId") or review.get("task_id")
            except OctoApiError as e:
                if e.status_code == 409:
                    stage_note = (
                        "prospect is not at ic-review — memo left as judged draft"
                    )
                else:
                    raise

        result = IcMemoResult(
            prospect_id=prospect_id,
            status="completed",
            memo=memo,
            retrieval=retrieval,
            verdict=verdict,
            ic_review_requested=review_requested,
            ic_review_task_id=task_id,
            stage_note=stage_note,
        )
        api.finish_run(
            run_id,
            status="completed",
            output=result.model_dump(),
            verdict={
                "complete": {"noul": verdict.complete_probability},
                "thesis": {"choice": verdict.thesis_band},
                "evidence": {"score": verdict.evidence_score},
                "retrieval": retrieval.model_dump(),
                "lineage": verdict.judge_lineage,
            },
        )
        return result
    except Exception as e:
        finish_failed(api, run_id, e)
        raise
