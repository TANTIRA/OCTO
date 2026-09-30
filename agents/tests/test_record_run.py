"""backlog #318 — a run_key that dedupes to a different workflow, subject or
input must not read back another request's output: `_record_run` verifies the
replayed row against the request and refuses on mismatch. And a body's
tenant/prospect pair is checked against the platform's record of the owning
tenant before any run is booked."""

from typing import Any

import pytest

from octo_agents.api_client import OctoApiClient, OctoApiError
from octo_agents.tools import SubjectNotInTenantError
from octo_agents.workflows.due_diligence import run_due_diligence
from octo_agents.workflows.ic_memo import run_ic_memo
from octo_agents.workflows.screening_dd import (
    RunKeyCollisionError,
    _record_run,
    load_prospect_in_tenant,
    run_screening_dd,
)


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


def test_input_mismatch_on_replay_is_refused() -> None:
    # Same subject, different request under a reused key: the stored output
    # answers another input and must not be served.
    with pytest.raises(RunKeyCollisionError):
        _run(
            {
                "id": "run-9",
                "workflow": "screening-dd",
                "subjectType": "prospect",
                "subjectId": "p-1",
                "input": {"prospect_id": "p-1", "question": "another"},
                "status": "completed",
                "output": {"memo": "another request's"},
            }
        )


def test_workflow_mismatch_on_replay_is_refused() -> None:
    with pytest.raises(RunKeyCollisionError):
        _run(
            {
                "id": "run-9",
                "workflow": "ic-memo",
                "subjectType": "prospect",
                "subjectId": "p-1",
                "input": {"prospect_id": "p-1"},
                "status": "completed",
                "output": {"memo": "an ic memo"},
            }
        )


def test_replay_with_matching_workflow_subject_and_input_reads_back() -> None:
    run_id, replayed = _run(
        {
            "id": "run-1",
            "workflow": "screening-dd",
            "subjectType": "prospect",
            "subjectId": "p-1",
            "input": {"prospect_id": "p-1"},
            "status": "completed",
            "output": {"memo": "cached"},
        }
    )
    assert (run_id, replayed) == ("run-1", {"memo": "cached"})


class TenantApi(OctoApiClient):
    """get_prospect answers from a fixed owner; record_run must never be reached
    when the pair is mismatched."""

    def __init__(self, *, owner: str | None = None, status: int | None = None) -> None:
        self._owner = owner
        self._status = status
        self.recorded = False

    def get_prospect(self, prospect_id: str) -> Any:
        if self._status is not None:
            raise OctoApiError(self._status, "")
        return {"id": prospect_id, "tenantId": self._owner}

    def record_run(self, **kwargs: Any) -> Any:
        self.recorded = True
        return {"id": "run-1"}


def test_prospect_in_tenant_is_returned() -> None:
    api = TenantApi(owner="0F0E8A52-0000-4000-8000-000000000001")
    prospect = load_prospect_in_tenant(api, "p-1", "0f0e8a52-0000-4000-8000-000000000001")
    assert prospect["id"] == "p-1"


@pytest.mark.parametrize(
    "api",
    [TenantApi(owner="tenant-B"), TenantApi(owner=None), TenantApi(status=404)],
    ids=["other-tenant", "no-owner", "platform-404"],
)
def test_prospect_outside_tenant_is_refused(api: TenantApi) -> None:
    with pytest.raises(SubjectNotInTenantError):
        load_prospect_in_tenant(api, "p-1", "tenant-A")


def test_platform_error_other_than_404_propagates() -> None:
    with pytest.raises(OctoApiError):
        load_prospect_in_tenant(TenantApi(status=503), "p-1", "tenant-A")


@pytest.mark.parametrize("run", [run_screening_dd, run_due_diligence, run_ic_memo])
def test_mismatched_pair_books_no_run(run: Any) -> None:
    # Tenant A's request naming tenant B's prospect is refused before the run
    # is recorded — nothing lands on either tenant's spine.
    api = TenantApi(owner="tenant-B")
    with pytest.raises(SubjectNotInTenantError):
        run(
            agent_model=None,
            judge=None,
            api=api,
            prospect_id="p-1",
            tenant_id="tenant-A",
            run_key="rk-1",
            models={},
        )
    assert not api.recorded
