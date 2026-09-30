"""HTTP surface — docs stay closed, and the bearer gate answers 503 when
unconfigured, 401 on a wrong token, and lets a valid token reach the
workflow's own checks (feature flag) rather than failing at the edge."""

from collections.abc import Iterator
from typing import Any

import pytest
from fastapi.testclient import TestClient

from octo_agents.config import get_settings
from octo_agents.server import app


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
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
