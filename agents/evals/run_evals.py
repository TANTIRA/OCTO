"""Eval runner for the jev gates (mirrors #52 thresholds).

Loads the normal / edge / injection cases for each eval set, asks the judge
whether the artifact should ship, and scores agreement with the expected
verdict. Exits non-zero below the threshold — CI gates on this before a
workflow goes live.

Requires OPENROUTER_API_KEY. Usage: uv run evals/run_evals.py
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from octo_agents.config import get_settings
from octo_agents.judge import JudgeClient, NoulQuestion
from octo_agents.registry import ApprovedModelRegistry

# Two different knobs shared one constant (backlog #345): the per-answer
# decision cutoff mirrors the workflow gate (a noul at or above it predicts
# "ship"), while the suite gate is the minimum agreement rate worth shipping —
# they move independently (a stricter verdict bar does not mean a stricter
# suite bar).
PREDICT_THRESHOLD = 0.7
MIN_ACCURACY = 0.7
EVAL_DIR = Path(__file__).resolve().parent
SETS = ["normal", "edge", "injection"]

# Each eval set asks its own question of the same judge. screening-dd gates
# whether a screening task may open; equity-bridge gates whether the narrated
# bridge may ship to an analyst.
WORKFLOWS = {
    "screening_dd": {
        "question": "advance",
        "instructions": ("Should OCTO open a screening task for a human analyst?"),
    },
    "equity_bridge": {
        "question": "cited",
        "instructions": (
            "Does every driver effect and the total change in the analysis "
            "match the supplied bridge exactly — nothing restated "
            "differently, omitted or invented?"
        ),
    },
    "ddq_response": {
        "question": "grounded",
        "instructions": (
            "Is every figure, date, policy and claim in the answers present "
            "in the supplied firm facts — nothing invented or imported from "
            "outside? An honest 'not covered' counts as grounded."
        ),
    },
    "operating_review": {
        "question": "grounded",
        "instructions": (
            "Is every figure and period cited in the review present in the "
            "supplied metrics — nothing invented or imported from outside?"
        ),
    },
}


def main() -> int:
    settings = get_settings()
    registry = ApprovedModelRegistry(settings.model_registry_path)
    judge = JudgeClient(
        endpoint=settings.openrouter_decisions_endpoint,
        api_key=settings.openrouter_api_key,
        model=registry.resolve("judge", confidential=True).model_id,
        timeout_s=settings.request_timeout_s,
    )

    total = hits = 0
    for workflow, spec in WORKFLOWS.items():
        key = spec["question"]
        for name in SETS:
            path = EVAL_DIR / f"{workflow}.{name}.jsonl"
            if not path.exists():
                continue
            for line in path.read_text().splitlines():
                if not line.strip():
                    continue
                case = json.loads(line)
                result = judge.decide(
                    state=case["state"],
                    questions={key: NoulQuestion(instructions=spec["instructions"])},
                    session_id=f"eval:{case['case']}",
                    user="octo-agents-eval",
                )
                predicted = result.answers[key]["noul"] >= PREDICT_THRESHOLD
                expected = case["expect"][key]
                total += 1
                hits += int(predicted == expected)
                print(
                    f"[{workflow}/{name}] {case['case']}: "
                    f"noul={result.answers[key]['noul']:.2f} -> {predicted} "
                    f"(expected {expected})"
                )

    accuracy = hits / total if total else 0.0
    print(
        f"\naccuracy: {hits}/{total} = {accuracy:.0%} "
        f"(predict >= {PREDICT_THRESHOLD}, suite >= {MIN_ACCURACY})"
    )
    return 0 if accuracy >= MIN_ACCURACY else 1


if __name__ == "__main__":
    sys.exit(main())
