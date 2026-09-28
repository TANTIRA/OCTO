"""Kotlin api client — the sidecar's only tool surface (ADR-0005 tool boundary).

Agents get read endpoints plus two mediated writes: request a screening (which
opens a workflow task inside the platform) and draft a report (which passes the
report approval gate). There is no direct database access, no ledger write and
no approval path here by construction.
"""

from typing import Any

import httpx


class OctoApiError(RuntimeError):
    def __init__(self, status_code: int, body: str) -> None:
        super().__init__(f"octo api call failed with status {status_code}")
        self.status_code = status_code


class OctoApiClient:
    def __init__(
        self,
        base_url: str,
        token: str,
        *,
        timeout_s: float = 60.0,
        client: httpx.Client | None = None,
    ) -> None:
        if not token:
            raise ValueError("octo_agent_token is required for api calls")
        self._client = client or httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {token}"},
            timeout=timeout_s,
        )

    def _get(self, path: str) -> Any:
        r = self._client.get(path)
        if r.status_code not in range(200, 300):
            raise OctoApiError(r.status_code, r.text[:512])
        return r.json()

    def _post(self, path: str, body: dict[str, Any] | None = None) -> Any:
        r = self._client.post(path, json=body or {})
        if r.status_code not in range(200, 300):
            raise OctoApiError(r.status_code, r.text[:512])
        return r.json() if r.text else {}

    # Reads — same JWT + RBAC surface a human service principal would use.
    def get_prospect(self, prospect_id: str) -> Any:
        return self._get(f"/api/v1/prospects/{prospect_id}")

    def list_prospect_events(self, prospect_id: str) -> Any:
        return self._get(f"/api/v1/prospects/{prospect_id}/events")

    def get_asset(self, asset_id: str) -> Any:
        return self._get(f"/api/v1/assets/{asset_id}")

    def get_dataset(self, dataset_id: str, **params: Any) -> Any:
        query = "&".join(f"{k}={v}" for k, v in params.items())
        return self._get(f"/api/v1/data/{dataset_id}?{query}" if query else f"/api/v1/data/{dataset_id}")

    # Mediated writes — these open platform workflows, they never write the
    # ledger and their output still passes the human approval gates.
    def request_screening(self, prospect_id: str) -> Any:
        return self._post(f"/api/v1/prospects/{prospect_id}/screen")

    def draft_report(
        self,
        report_type: str,
        position_source_type: str,
        measures: list[str],
    ) -> Any:
        return self._post(
            "/api/v1/reports",
            {
                "type": report_type,
                "positionSourceType": position_source_type,
                "measures": measures,
            },
        )
