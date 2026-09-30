"""Due-diligence workstreams — parallel read-only subagents (ADR-0005 F3).

Flow, same separation of duties as screening-dd:
  1. The prospect's events are jev-scored for relevance (F2's score_events —
     the retrieval gate composes across workflows).
  2. The orchestrator deepagent delegates each workstream to a subagent via the
     task tool — isolated context per stream, the same read-only tool surface,
     so a market analyst cannot see the legal thread's messages and neither can
     write. One invoke: the task calls run in parallel.
  3. jev bands every workstream low/medium/high/blocker and scores the dossier's
     completeness — the prioritization is a typed answer, not parsed prose.
  4. Workflow code opens one EVIDENCE_REQUEST task per high-or-blocker
     workstream through POST /prospects/{id}/dd-evidence — idempotent per
     stream, decided by a human inside the platform like every task.
"""

from typing import Any, Literal

from deepagents import SubAgent, create_deep_agent
from pydantic import BaseModel

from ..api_client import OctoApiClient, OctoApiError
from ..judge import ChoiceQuestion, JudgeClient, ScoreQuestion
from ..tools import read_tools
from .screening_dd import (
    _record_run,
    extract_final_text,
    finish_failed,
    load_prospect_in_tenant,
    preflight_gate,
    score_events,
)
from .warm_context import warm_prompt

WORKSTREAMS = ("market", "financial", "legal", "operational")

# Only these bands mint a task — medium is a note in the findings, not a ticket.
TASK_BANDS = {"high", "blocker"}

_WORKSTREAM_PROMPTS = {
    "market": (
        "Analyze the prospect's market: size evidence, competitive position, and "
        "demand signals in the record. Report facts with their source, then the "
        "top market risks and what evidence would settle them."
    ),
    "financial": (
        "Analyze the prospect's financial record: reported figures, asset and "
        "ledger references, valuation hints. Never invent numbers — state what "
        "the record contains, what it lacks, and the top financial risks."
    ),
    "legal": (
        "Analyze the prospect's legal posture as the record shows it: structure, "
        "jurisdiction, counterparty and compliance signals. Report facts, then "
        "the top legal risks and the documents that would settle them."
    ),
    "operational": (
        "Analyze the prospect's operational surface: team signals, delivery or "
        "supply dependencies, and on-chain/operational evidence in the record. "
        "Report facts, then the top operational risks and missing evidence."
    ),
}

ORCHESTRATOR_PROMPT = """You are the OCTO due-diligence lead. For the prospect named in the
task, delegate EVERY workstream — market, financial, legal, operational — to its
subagent in a single step, then synthesize their reports into one dossier:

for each workstream: what the record shows, the top risks, and the evidence gaps
that would settle them. Close with a "gap register": the open questions grouped
by workstream. Facts only — pull record detail through the tools, never invent.

The excerpts in the task are already jev-scored as relevant to this prospect;
treat them as the record's signal and use tools for anything they do not cover."""


class WorkstreamBand(BaseModel):
    workstream: str
    band: str
    confidence: float


class DdTask(BaseModel):
    workstream: str
    task_id: str | None
    opened: bool


class DdResult(BaseModel):
    prospect_id: str
    status: Literal["completed", "refused"]
    dossier: str = ""
    bands: list[WorkstreamBand] = []
    completeness: float | None = None
    tasks: list[DdTask] = []
    task_errors: list[str] = []
    judge_lineage: dict[str, Any] = {}


def _band_workstreams(
    judge: JudgeClient,
    *,
    prospect_id: str,
    prospect_state: Any,
    dossier: str,
) -> tuple[list[WorkstreamBand], float, dict[str, Any]]:
    """One decide() call bands every workstream and scores the dossier —
    parallel typed questions, the way the screening verdict is built."""
    questions: dict[str, Any] = {
        ws: ChoiceQuestion(
            instructions=(
                f"Given the dossier's {ws} findings, what is this prospect's "
                f"{ws} risk level?"
            ),
            criteria={
                "low": "record looks sound; routine diligence suffices",
                "medium": "open questions but nothing blocking",
                "high": "a material gap or adverse signal needs evidence first",
                "blocker": "a finding that should stop the deal absent new evidence",
            },
        )
        for ws in WORKSTREAMS
    }
    questions["completeness"] = ScoreQuestion(
        instructions="How complete is this due-diligence dossier against the record?",
        criteria=["1-empty", "2-partial", "3-adequate", "4-thorough", "5-complete"],
    )
    result = judge.decide(
        state={
            "prospect_id": prospect_id,
            "prospect": prospect_state,
            "dossier": dossier,
        },
        questions=questions,
        session_id=f"due-diligence:{prospect_id}",
        user="octo-agents",
    )
    bands = [
        WorkstreamBand(
            workstream=ws,
            band=result.require(ws).get("choice"),
            confidence=result.require(ws).get("confidence", 0.0),
        )
        for ws in WORKSTREAMS
    ]
    return (
        bands,
        result.require_score("completeness"),
        {"model": result.model, "provider": result.provider, "request_id": result.id},
    )


def run_due_diligence(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    prospect_id: str,
    tenant_id: str,
    run_key: str,
    models: dict[str, str],
) -> DdResult:
    prospect_state = load_prospect_in_tenant(api, prospect_id, tenant_id)
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="due-diligence",
        run_key=run_key,
        subject_type="prospect",
        subject_id=prospect_id,
        input={"prospect_id": prospect_id},
        models=models,
    )
    try:
        if replayed is not None:
            return DdResult.model_validate(replayed)

        raw_events = api.list_prospect_events(prospect_id)
        events = raw_events if isinstance(raw_events, list) else []

        # The screening record already passed, but a prospect can land in DD with a
        # stale or gutted record — the same noul still gates the drafter spend.
        preflight = preflight_gate(
            judge, prospect_id=prospect_id, prospect_state=prospect_state, events=events
        )
        if not preflight.record_sufficient:
            result = DdResult(prospect_id=prospect_id, status="refused")
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

        context = warm_prompt(api, tenant_id, "")
        subagents: list[SubAgent] = [
            {
                "name": f"dd-{ws}",
                "description": f"{ws} due-diligence analyst — read-only",
                "system_prompt": _WORKSTREAM_PROMPTS[ws] + context,
                "tools": read_tools(api),
            }
            for ws in WORKSTREAMS
        ]
        agent = create_deep_agent(
            model=agent_model,
            tools=read_tools(api),
            system_prompt=ORCHESTRATOR_PROMPT + context,
            subagents=subagents,
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
                            f"Run due diligence on prospect {prospect_id}.\n\n"
                            "Relevant record excerpts, already scored for this task:\n"
                            f"{evidence}"
                        ),
                    )
                ]
            }
        )
        dossier = extract_final_text(result)

        bands, completeness, lineage = _band_workstreams(
            judge,
            prospect_id=prospect_id,
            prospect_state=prospect_state,
            dossier=dossier,
        )

        # Task opening is side-effectful — a throw must not strand the tasks
        # already minted. Per-band handling keeps the result truthful: a 409
        # (task already open) or any other API error is recorded instead of
        # failing the whole run.
        tasks: list[DdTask] = []
        task_errors: list[str] = []
        for band in bands:
            if band.band not in TASK_BANDS:
                continue
            try:
                resp = api.open_dd_evidence(
                    prospect_id, band.workstream, dossier[:2000]
                )
            except OctoApiError as e:
                if e.status_code != 409:
                    # Keep the platform's reason, not just the code (#343).
                    reason = f": {e.body[:200]}" if e.body else ""
                    task_errors.append(
                        f"{band.workstream}: HTTP {e.status_code}{reason}"
                    )
                tasks.append(
                    DdTask(
                        workstream=band.workstream,
                        task_id=None,
                        opened=False,
                    )
                )
                continue
            tasks.append(
                DdTask(
                    workstream=band.workstream,
                    task_id=resp.get("taskId"),
                    opened=bool(resp.get("opened")),
                )
            )

        result = DdResult(
            prospect_id=prospect_id,
            status="completed",
            dossier=dossier,
            bands=bands,
            completeness=completeness,
            tasks=tasks,
            task_errors=task_errors,
            judge_lineage=lineage,
        )
        api.finish_run(
            run_id,
            status="completed",
            output=result.model_dump(),
            verdict={
                **{b.workstream: {"choice": b.band} for b in bands},
                "completeness": {"score": completeness},
                "retrieval": retrieval.model_dump(),
                "lineage": lineage,
            },
        )
        return result
    except Exception as e:
        finish_failed(api, run_id, e)
        raise
