"""F11 warm context — optional prompt decoration. It prepends the tenant's
standing brief when one is set, and is a no-op (never a failure) when the
setting is absent or the edge is unreachable (backlog #29)."""

from typing import Any

from octo_agents.api_client import OctoApiClient, OctoApiError
from octo_agents.workflows.warm_context import warm_prompt

BASE = "You are the OCTO analyst."


class FakeApi(OctoApiClient):
    def __init__(self, *, context: Any = None, raises: Exception | None = None) -> None:
        self._context = context
        self._raises = raises

    def get_agent_context(self, tenant_id: str) -> Any:
        if self._raises is not None:
            raise self._raises
        return self._context


def test_prepends_brief_when_set() -> None:
    api = FakeApi(context={"warmContext": "Fund II: infra buyouts, ESG-screened."})
    out = warm_prompt(api, "t-1", BASE)
    assert out.startswith(BASE)
    assert "infra buyouts" in out


def test_absent_setting_is_a_noop() -> None:
    assert warm_prompt(FakeApi(context={}), "t-1", BASE) == BASE
    assert warm_prompt(FakeApi(context=None), "t-1", BASE) == BASE
    assert warm_prompt(FakeApi(context={"warmContext": "  "}), "t-1", BASE) == BASE


def test_edge_failure_falls_back_to_base() -> None:
    api = FakeApi(raises=OctoApiError(503, "unreachable"))
    assert warm_prompt(api, "t-1", BASE) == BASE
