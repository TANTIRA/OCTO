"""Eval runner for the screening-dd judge gate (mirrors #52 thresholds).

Loads the normal / edge / injection cases, asks the judge whether a screening
task should open, and scores agreement with the expected verdict. Exits
non-zero below the threshold — CI gates on this before a workflow goes live.

Requires OPENROUTER_API_KEY. Usage: uv run evals/run_evals.py
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from octo_agents.config import get_settings  # noqa: E402
from octo_agents.judge import JudgeClient, NoulQuestion  # noqa: E402
from octo_agents.registry import ApprovedModelRegistry  # noqa: E402

THRESHOLD = 0.7
EVAL_DIR = Path(__file__).resolve().parent
SETS = ["normal", "edge", "injection"]


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
    for name in SETS:
        path = EVAL_DIR / f"screening_dd.{name}.jsonl"
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            case = json.loads(line)
            result = judge.decide(
                state=case["state"],
                questions={
                    "advance": NoulQuestion(
                        instructions=(
                            "Should OCTO open a screening task for a human analyst "
                            "to advance this prospect?"
                        )
                    )
                },
                session_id=f"eval:{case['case']}",
                user="octo-agents-eval",
            )
            predicted = result.answers["advance"]["noul"] >= THRESHOLD
            expected = case["expect"]["advance"]
            total += 1
            hits += int(predicted == expected)
            print(
                f"[{name}] {case['case']}: noul={result.answers['advance']['noul']:.2f} "
                f"-> {predicted} (expected {expected})"
            )

    accuracy = hits / total if total else 0.0
    print(f"\naccuracy: {hits}/{total} = {accuracy:.0%} (threshold {THRESHOLD})")
    return 0 if accuracy >= THRESHOLD else 1


if __name__ == "__main__":
    sys.exit(main())
