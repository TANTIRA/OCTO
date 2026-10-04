"""HTTP surface — docs stay closed, and the bearer gate answers 503 when
unconfigured, 401 on a wrong token, and lets a valid token reach the
workflow's own checks (feature flag) rather than failing at the edge."""

import time
from collections.abc import Iterator
from contextlib import contextmanager
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi.testclient import TestClient

from octo_agents import deadline, server
from octo_agents.config import get_settings
from octo_agents.deadline import DeadlineExceeded
from octo_agents.server import app
from octo_agents.tools import SubjectNotInTenantError
from octo_agents.workflows.screening_dd import (
    RunInProgressError,
    RunKeyCollisionError,
    RunPreviouslyFailedError,
)


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    # Default OCTO_API_BASE_URL is the internal compose hop — plaintext http
    # needs the explicit opt-in the deployment sets (octo_agents/config.py).
    monkeypatch.setenv("OCTO_AGENTS_INSECURE_HTTP", "true")
    get_settings.cache_clear()
    yield TestClient(app)
    get_settings.cache_clear()


def test_healthz_is_open(client: TestClient) -> None:
    assert client.get("/healthz").json() == {"status": "ok"}


def test_docs_and_openapi_are_closed(client: TestClient) -> None:
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404
    assert client.get("/openapi.json").status_code == 404


def test_unconfigured_token_answers_503(client: TestClient) -> None:
    res = client.post("/v1/workflows/calibration", json={"tenant_id": "t-1"})
    assert res.status_code == 503


def test_wrong_bearer_answers_401(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OCTO_AGENTS_TOKEN", "s3cret")
    get_settings.cache_clear()
    res = client.post(
        "/v1/workflows/calibration",
        json={"tenant_id": "t-1"},
        headers={"Authorization": "Bearer wrong"},
    )
    assert res.status_code == 401


def test_valid_bearer_reaches_the_workflow(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OCTO_AGENTS_TOKEN", "s3cret")
    get_settings.cache_clear()
    res = client.post(
        "/v1/workflows/calibration",
        json={"tenant_id": "t-1"},
        headers={"Authorization": "Bearer s3cret"},
    )
    # Past the bearer gate — the workflow's own flag refuses, not the edge.
    assert res.status_code == 503
    assert "feature-flagged off" in res.json()["detail"]


def test_non_ascii_bearer_answers_401_not_500(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OCTO_AGENTS_TOKEN", "s3cret")
    get_settings.cache_clear()
    res = client.post(
        "/v1/workflows/calibration",
        json={"tenant_id": "t-1"},
        headers={"Authorization": b"Bearer s\xe9cret"},
    )
    assert res.status_code == 401


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (SubjectNotInTenantError("prospect p-1", "t-1"), 404),
        (RunKeyCollisionError("rk-1", "prospect/p-1", "prospect/p-2"), 409),
        (DeadlineExceeded("judge call"), 504),
        (RunInProgressError("rk-1", "run-1"), 409),
        (RunPreviouslyFailedError("rk-1", "run-1"), 409),
    ],
)
def test_binding_errors_map_to_client_statuses(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, error: Exception, code: int
) -> None:
    monkeypatch.setenv("OCTO_AGENTS_TOKEN", "s3cret")
    monkeypatch.setenv("OCTO_AGENT_TOKEN", "api-token")
    monkeypatch.setenv("OCTO_AGENTS_CALIBRATION_ENABLED", "true")
    get_settings.cache_clear()

    def refuse(**_: object) -> None:
        raise error

    monkeypatch.setattr(server, "run_calibration", refuse)
    res = client.post(
        "/v1/workflows/calibration",
        json={"tenant_id": "t-1"},
        headers={"Authorization": "Bearer s3cret"},
    )
    assert res.status_code == code
    assert res.json()["detail"] == str(error)


def test_workflow_runs_under_the_configured_deadline(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OCTO_AGENTS_TOKEN", "s3cret")
    monkeypatch.setenv("OCTO_AGENT_TOKEN", "api-token")
    monkeypatch.setenv("OCTO_AGENTS_CALIBRATION_ENABLED", "true")
    monkeypatch.setenv("RUN_DEADLINE_S", "42")
    get_settings.cache_clear()
    seen: list[float] = []

    def observe(**_: object) -> Any:
        seen.append(deadline._current.get().remaining())  # type: ignore[union-attr]
        return SimpleNamespace(model_dump=lambda: {"status": "completed"})

    monkeypatch.setattr(server, "run_calibration", observe)
    res = client.post(
        "/v1/workflows/calibration",
        json={"tenant_id": "t-1"},
        headers={"Authorization": "Bearer s3cret"},
    )
    assert res.status_code == 200
    assert len(seen) == 1 and 40 < seen[0] <= 42


def _arrived(seconds_ago: float) -> Any:
    """Stamps acceptance in the middleware, where the real clock starts.

    The threadpool copies context only once a worker is free, so a stamp set
    inside the endpoint — or inside a sync dependency, which runs on its own
    worker — never includes the time the request sat in the queue.
    """

    def clock() -> float:
        return time.monotonic() - seconds_ago

    @contextmanager
    def mark() -> Iterator[None]:
        with deadline.note_request_arrival(clock=clock):
            yield

    return mark


def test_time_waiting_for_a_worker_counts_against_the_run_budget(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OCTO_AGENTS_TOKEN", "s3cret")
    monkeypatch.setenv("OCTO_AGENT_TOKEN", "api-token")
    monkeypatch.setenv("OCTO_AGENTS_CALIBRATION_ENABLED", "true")
    monkeypatch.setenv("RUN_DEADLINE_S", "100")
    get_settings.cache_clear()
    seen: list[float] = []

    def observe(**_: object) -> Any:
        seen.append(deadline._current.get().remaining())  # type: ignore[union-attr]
        return SimpleNamespace(model_dump=lambda: {"status": "completed"})

    monkeypatch.setattr(server, "note_request_arrival", _arrived(30))
    monkeypatch.setattr(server, "run_calibration", observe)
    res = client.post(
        "/v1/workflows/calibration",
        json={"tenant_id": "t-1"},
        headers={"Authorization": "Bearer s3cret"},
    )
    assert res.status_code == 200
    assert len(seen) == 1 and 68 < seen[0] <= 70


def test_a_request_queued_past_the_budget_does_not_start_the_workflow(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OCTO_AGENTS_TOKEN", "s3cret")
    monkeypatch.setenv("OCTO_AGENT_TOKEN", "api-token")
    monkeypatch.setenv("OCTO_AGENTS_CALIBRATION_ENABLED", "true")
    monkeypatch.setenv("RUN_DEADLINE_S", "42")
    get_settings.cache_clear()
    started: list[int] = []

    def observe(**_: object) -> Any:
        started.append(1)
        return SimpleNamespace(model_dump=lambda: {"status": "completed"})

    monkeypatch.setattr(server, "note_request_arrival", _arrived(50))
    monkeypatch.setattr(server, "run_calibration", observe)
    res = client.post(
        "/v1/workflows/calibration",
        json={"tenant_id": "t-1"},
        headers={"Authorization": "Bearer s3cret"},
    )
    assert res.status_code == 504
    assert started == []
    assert res.json()["detail"] == "run deadline exceeded before workflow start"
