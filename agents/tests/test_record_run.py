"""backlog #318 — a run_key that dedupes to a different subject must not read
back the wrong subject's output. `_record_run` verifies the replayed row's
subject against the request and refuses on mismatch."""

from typing import Any

import pytest

from octo_agents.api_client import OctoApiClient
from octo_agents.workflows.screening_dd import RunKeyCollisionError, _record_run


class FakeApi(OctoApiClient):
    def __init__(self, recorded: dict[str, Any]) -> None:
        self._recorded = recorded

    def record_run(self, **kwargs: Any) -> Any:
        return self._recorded


def _run(recorded: dict[str, Any]) -> tuple[str, Any | None]:
    return _record_run(
        FakeApi(recorded),
        tenant_id="t-1",
        workflow="screening-dd",
        run_key="rk-1",
        subject_type="prospect",
        subject_id="p-1",
        input={"prospect_id": "p-1"},
        models={},
    )


def test_fresh_insert_returns_id_and_no_replay() -> None:
    run_id, replayed = _run({"id": "run-1"})
    assert run_id == "run-1"
    assert replayed is None


def test_matching_replay_returns_cached_output() -> None:
    run_id, replayed = _run(
        {
            "id": "run-1",
            "subjectType": "prospect",
            "subjectId": "p-1",
            "status": "completed",
            "output": {"memo": "cached"},
        }
    )
    assert run_id == "run-1"
    assert replayed == {"memo": "cached"}


def test_subject_mismatch_on_replay_is_refused() -> None:
    with pytest.raises(RunKeyCollisionError):
        _run(
            {
                "id": "run-9",
                "subjectType": "prospect",
                "subjectId": "p-OTHER",
                "status": "completed",
                "output": {"memo": "someone else's"},
            }
        )


def test_subject_mismatch_refused_even_while_running() -> None:
    # An in-flight run under the same key on a different subject is still a
    # collision — refuse before doing any work.
    with pytest.raises(RunKeyCollisionError):
        _run(
            {
                "id": "run-9",
                "subjectType": "prospect",
                "subjectId": "p-OTHER",
                "status": "running",
            }
        )
