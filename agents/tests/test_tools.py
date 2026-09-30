"""#318 — the drafter's read tools only ever hand the model records of the
run's tenant. The platform principal may see several tenants, so a record the
api returns but another tenant owns must read as "not found", like an unknown id."""

from typing import Any

import pytest

from octo_agents.api_client import OctoApiClient, OctoApiError
from octo_agents.tools import read_tools

TENANT_A = "0f0e8a52-0000-4000-8000-00000000000a"
TENANT_B = "0f0e8a52-0000-4000-8000-00000000000b"


class MultiTenantApi(OctoApiClient):
    """A principal that belongs to both tenants: the api answers for either."""

    def __init__(self) -> None:
        self.prospects = {
            "p-a": {"id": "p-a", "tenantId": TENANT_A.upper(), "name": "PT Alpha"},
            "p-b": {"id": "p-b", "tenantId": TENANT_B, "name": "PT Bravo"},
            "p-none": {"id": "p-none", "name": "PT Orphan"},
        }
        self.assets = {
            "a-a": {"id": "a-a", "tenantId": TENANT_A, "name": "Alpha Asset"},
            "a-b": {"id": "a-b", "tenantId": TENANT_B, "name": "Bravo Asset"},
        }
        self.event_reads: list[str] = []

    def get_prospect(self, prospect_id: str) -> Any:
        if prospect_id == "p-down":
            raise OctoApiError(503, "unavailable")
        if prospect_id not in self.prospects:
            raise OctoApiError(404, "")
        return self.prospects[prospect_id]

    def list_prospect_events(self, prospect_id: str) -> Any:
        self.event_reads.append(prospect_id)
        return [{"seq": 1, "eventType": "registered", "rationale": f"{prospect_id} secret"}]

    def get_asset(self, asset_id: str) -> Any:
        if asset_id not in self.assets:
            raise OctoApiError(404, "")
        return self.assets[asset_id]


def _tools(api: OctoApiClient) -> dict[str, Any]:
    return {t.name: t for t in read_tools(api, TENANT_A)}


def test_own_tenant_records_reach_the_model() -> None:
    api = MultiTenantApi()
    tools = _tools(api)
    assert "PT Alpha" in tools["get_prospect"].invoke({"prospect_id": "p-a"})
    assert "Alpha Asset" in tools["get_asset"].invoke({"asset_id": "a-a"})
    assert "p-a secret" in tools["list_prospect_events"].invoke({"prospect_id": "p-a"})


@pytest.mark.parametrize("prospect_id", ["p-b", "p-none", "p-unknown"])
def test_foreign_prospect_reads_as_not_found(prospect_id: str) -> None:
    tools = _tools(MultiTenantApi())
    out = tools["get_prospect"].invoke({"prospect_id": prospect_id})
    assert out == f"prospect {prospect_id} not found"


@pytest.mark.parametrize("prospect_id", ["p-b", "p-none", "p-unknown"])
def test_foreign_prospect_events_are_never_read(prospect_id: str) -> None:
    api = MultiTenantApi()
    out = _tools(api)["list_prospect_events"].invoke({"prospect_id": prospect_id})
    assert out == f"prospect {prospect_id} not found"
    assert api.event_reads == []


@pytest.mark.parametrize("asset_id", ["a-b", "a-unknown"])
def test_foreign_asset_reads_as_not_found(asset_id: str) -> None:
    out = _tools(MultiTenantApi())["get_asset"].invoke({"asset_id": asset_id})
    assert out == f"asset {asset_id} not found"
    assert "Bravo" not in out


def test_platform_error_other_than_404_propagates() -> None:
    with pytest.raises(OctoApiError):
        _tools(MultiTenantApi())["get_prospect"].invoke({"prospect_id": "p-down"})
