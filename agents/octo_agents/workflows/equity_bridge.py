"""Equity-bridge quarterly analysis — narrates the deterministic bridge (F6).

The Investment Analyst runs an equity-bridge at quarter end
(docs/user-journey.md). The platform computes it with `valueBridge`
(`modules/analytics/.../Bridge.kt`, quantitative-methodology §4.3): entry and
exit points, the driver effects, and the change — all inline in the request.
The drafter gets no tools, so every figure in the narrative traces to the
computed bridge; jev's citation gate verifies exactly that before the
analysis ships. Nothing here recomputes the bridge — the numbers are the
platform's, the prose is the only new content.
"""

import json
from typing import Any, Literal

from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel

from ..api_client import OctoApiClient
from ..judge import ChoiceQuestion, JudgeClient, NoulQuestion
from .screening_dd import _record_run

BRIDGE_PROMPT = """You are the OCTO investment analyst. Narrate the equity-value bridge
between the entry and exit points of the company whose computed effects follow.
Explain what moved the equity value over the period: which drivers added and
which subtracted, and what that says about the quarter. Quote every driver
effect and the total change exactly as given — never restate a figure
differently, never cite a driver that is not in the supplied effects, never
introduce a number from outside. One paragraph."""

# Operating points — the analysis ships only when every citation is exact and
# every driver is addressed.
CITED_THRESHOLD = 0.7
COMPLETE_THRESHOLD = 0.6


class BridgeVerdict(BaseModel):
    ship: bool
    cited_probability: float
    complete_probability: float
    register_band: str | None
    judge_lineage: dict[str, Any]


class BridgeResult(BaseModel):
    company: str
    status: Literal["completed", "refused"]
    analysis: str = ""
    verdict: BridgeVerdict | None = None
    note: str | None = None


def judge_bridge(
    judge: JudgeClient,
    *,
    company: str,
    effects: dict[str, Any],
    analysis: str,
) -> BridgeVerdict:
    result = judge.decide(
        state={"company": company, "effects": effects, "analysis": analysis},
        questions={
            "cited": NoulQuestion(
                instructions=(
                    "Does every driver effect and the total change in the "
                    "analysis match the supplied bridge exactly — nothing "
                    "restated differently or invented?"
                )
            ),
            "complete": NoulQuestion(
                instructions=(
                    "Does the analysis address every supplied driver effect "
                    "and state the total change, skipping none?"
                )
            ),
            "register": ChoiceQuestion(
                instructions="Which register does the analysis read as?",
                criteria={
                    "memo-ready": (
                        "measured, factual, suitable for an IC or LP audience"
                    ),
                    "internal": "draft-quality, speculative, or over-claiming",
                },
            ),
        },
        session_id=f"equity-bridge:{company}",
        user="octo-agents",
    )
    cited = result.answers["cited"]["noul"]
    complete = result.answers["complete"]["noul"]
    register = result.answers["register"].get("choice")
    return BridgeVerdict(
        ship=(
            cited >= CITED_THRESHOLD
            and complete >= COMPLETE_THRESHOLD
            and register != "internal"
        ),
        cited_probability=cited,
        complete_probability=complete,
        register_band=register,
        judge_lineage={
            "model": result.model,
            "provider": result.provider,
            "request_id": result.id,
        },
    )


def run_equity_bridge(
    *,
    agent_model: Any,
    judge: JudgeClient,
    api: OctoApiClient,
    tenant_id: str,
    company: str,
    entry: dict[str, Any],
    exit: dict[str, Any],
    effects: dict[str, Any],
    change: str,
    method: str,
    local_currency: str,
    reporting_currency: str,
    run_key: str,
    models: dict[str, str],
) -> BridgeResult:
    run_id, replayed = _record_run(
        api,
        tenant_id=tenant_id,
        workflow="equity-bridge",
        run_key=run_key,
        subject_type="company",
        subject_id=company,
        input={
            "company": company,
            "entry": entry,
            "exit": exit,
            "effects": effects,
            "change": change,
            "method": method,
            "local_currency": local_currency,
            "reporting_currency": reporting_currency,
        },
        models=models,
    )
    if replayed is not None:
        return BridgeResult.model_validate(replayed)

    try:
        if not effects:
            result = BridgeResult(
                company=company,
                status="refused",
                note="no bridge effects to narrate",
            )
            api.finish_run(run_id, status="refused", output=result.model_dump())
            return result

        # The drafter gets no tools: the computed bridge is the whole evidence
        # base — a figure has nowhere else to come from.
        facts = {
            "company": company,
            "local_currency": local_currency,
            "reporting_currency": reporting_currency,
            "method": method,
            "entry": entry,
            "exit": exit,
            "effects": effects,
            "change": change,
        }
        message = agent_model.invoke(
            [
                SystemMessage(content=BRIDGE_PROMPT),
                HumanMessage(
                    content=(
                        f"Narrate the equity bridge for {company}:\n"
                        f"{json.dumps(facts, default=str)}"
                    )
                ),
            ]
        )
        analysis = (
            message.content
            if isinstance(message.content, str)
            else str(message.content)
        )

        verdict = judge_bridge(judge, company=company, effects=facts, analysis=analysis)
        result = BridgeResult(
            company=company,
            status="completed" if verdict.ship else "refused",
            analysis=analysis,
            verdict=verdict,
            note=None
            if verdict.ship
            else "the jev gate could not verify the analysis's citations",
        )
        api.finish_run(
            run_id,
            status=result.status,
            output=result.model_dump(),
            verdict={
                "cited": {"noul": verdict.cited_probability},
                "complete": {"noul": verdict.complete_probability},
                "register": {"choice": verdict.register_band},
                "lineage": verdict.judge_lineage,
            },
        )
        return result
    except Exception as e:
        api.finish_run(run_id, status="failed", error=str(e)[:2000])
        raise
