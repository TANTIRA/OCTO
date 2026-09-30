"""backlog #324/#325 — the client factories return 503 (not 500) when their
config is blank, and reuse one client per config tuple instead of leaking a
fresh httpx.Client per request."""

import pytest
from fastapi import HTTPException

from octo_agents import server
from octo_agents.config import Settings


def _settings(**over: object) -> Settings:
    base = dict(
        openrouter_api_key="k",
        octo_agent_token="t",
        octo_agents_token="in",
    )
    base.update(over)
    return Settings(**base)  # type: ignore[arg-type]


def setup_function() -> None:
    # The factories are process-cached; clear so each test sees fresh state.
    server._api_client.cache_clear()
    server._judge_client.cache_clear()


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
