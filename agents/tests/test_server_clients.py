"""backlog #324/#325 — the client factories return 503 (not 500) when their
config is blank, and reuse one client per config tuple instead of leaking a
fresh httpx.Client per request."""

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from octo_agents import server
from octo_agents.chat import drafter_model
from octo_agents.config import Settings


def _settings(**over: object) -> Settings:
    base: dict[str, object] = {
        "openrouter_api_key": "k",
        "octo_agent_token": "t",
        "octo_agents_token": "in",
        "octo_agents_insecure_http": True,
    }
    base.update(over)
    return Settings(**base)  # type: ignore[arg-type]


def setup_function() -> None:
    # The clients are process-shared; close and drop so each test starts fresh.
    server.close_clients()


def test_api_missing_token_is_503() -> None:
    with pytest.raises(HTTPException) as ei:
        server._api(_settings(octo_agent_token=""))
    assert ei.value.status_code == 503


def test_judge_missing_key_is_503() -> None:
    settings = _settings(openrouter_api_key="")
    with pytest.raises(HTTPException) as ei:
        server._judge(settings, server._registry(settings))
    assert ei.value.status_code == 503


def test_api_client_is_reused_across_calls() -> None:
    settings = _settings()
    assert server._api(settings) is server._api(settings)


def test_api_client_rebuilds_on_config_change() -> None:
    a = server._api(_settings(octo_api_base_url="http://a:8080"))
    b = server._api(_settings(octo_api_base_url="http://b:8080"))
    assert a is not b


def test_judge_client_is_reused_across_calls() -> None:
    settings = _settings()
    registry = server._registry(settings)
    assert server._judge(settings, registry) is server._judge(settings, registry)


def test_drafter_timeout_is_milliseconds() -> None:
    # #326 — ChatOpenRouter takes ms; 60 s must not become a 60 ms budget.
    settings = _settings(request_timeout_s=60.0)
    model = drafter_model(settings, server._registry(settings))
    assert model.request_timeout == 60_000


def test_shutdown_closes_every_shared_client() -> None:
    settings = _settings()
    registry = server._registry(settings)
    with TestClient(server.app):  # runs the lifespan: startup, then shutdown
        api = server._api(settings)
        judge = server._judge(settings, registry)
    assert api._client.is_closed
    assert judge._client.is_closed
    # A later request builds a fresh, open client rather than a closed one.
    assert server._api(settings) is not api
