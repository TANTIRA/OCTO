"""backlog #15 — a crashed run must land `failed` for F4, but the bookkeeping
call must never mask the original error. `finish_failed` swallows a secondary
`finish_run` failure and lets the caller's real exception propagate."""

from typing import Any

from octo_agents.api_client import OctoApiClient, OctoApiError
from octo_agents.workflows.screening_dd import finish_failed


class RecordingApi(OctoApiClient):
    def __init__(self, *, raises: Exception | None = None) -> None:
        self.finished: dict[str, Any] | None = None
        self._raises = raises

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        if self._raises is not None:
            raise self._raises
        self.finished = {"run_id": run_id, **kwargs}
        return {}


def test_marks_failed_with_error_text() -> None:
    api = RecordingApi()
    finish_failed(api, "run-1", ValueError("boom"))
    assert api.finished is not None
    assert api.finished["status"] == "failed"
    assert "boom" in api.finished["error"]


def test_api_error_reason_reaches_the_run_record() -> None:
    # #343: a failed API call records why, not just `status N`.
    api = RecordingApi()
    finish_failed(api, "run-1", OctoApiError(422, '{"error":"bad shape"}'))
    assert api.finished is not None
    assert "422" in api.finished["error"]
    assert "bad shape" in api.finished["error"]


def test_bookkeeping_failure_is_swallowed() -> None:
    # finish_run itself throwing (the very edge outage that crashed the run)
    # must not raise from finish_failed — the caller's bare `raise` re-surfaces
    # the original error instead.
    api = RecordingApi(raises=OctoApiError(503, "edge down"))
    finish_failed(api, "run-1", RuntimeError("original failure"))  # must not raise


def test_error_text_is_truncated() -> None:
    api = RecordingApi()
    finish_failed(api, "run-1", ValueError("x" * 5000))
    assert api.finished is not None
    assert len(api.finished["error"]) <= 2000
