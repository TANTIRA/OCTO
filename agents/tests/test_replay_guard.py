"""backlog #345 — a replayed run's stored output is validated inside the
workflow's failure guard: a corrupt record marks the run failed via
finish_failed instead of crashing past the bookkeeping or serving bad data."""

from typing import Any

import pytest
from pydantic import ValidationError

from octo_agents.api_client import OctoApiClient
from octo_agents.workflows.calibration import run_calibration


class ReplayApi(OctoApiClient):
    """record_run echoes a closed run whose stored output no longer validates."""

    def __init__(self, *, output: Any) -> None:
        self.finished: dict[str, Any] | None = None
        self._output = output

    def record_run(self, **kwargs: Any) -> Any:
        return {
            "id": "run-1",
            "status": "completed",
            "subjectType": "tenant",
            "subjectId": "t-1",
            "output": self._output,
        }

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        self.finished = {"run_id": run_id, **kwargs}
        return {}


def test_corrupt_replayed_output_marks_the_run_failed() -> None:
    api = ReplayApi(output={"status": "definitely-not-a-calibration-result"})
    with pytest.raises(ValidationError):
        run_calibration(
            api=api,
            tenant_id="t-1",
            run_key="rk-1",
            models={},
        )
    assert api.finished is not None
    assert api.finished["status"] == "failed"


def test_wellformed_replayed_output_reads_back() -> None:
    output = {
        "tenant_id": "t-1",
        "status": "completed",
        "analyzed": 0,
        "decided": 0,
        "stats": {},
        "disagreements": [],
        "eval_cases": [],
        "suggestions": [],
    }
    api = ReplayApi(output=output)
    result = run_calibration(api=api, tenant_id="t-1", run_key="rk-1", models={})
    assert result.status == "completed"
    assert api.finished is None  # a replay never re-finishes the run
