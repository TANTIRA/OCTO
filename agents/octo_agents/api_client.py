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

    def list_pipeline(self, tenant_id: str, stage: str, limit: int = 50) -> Any:
        return self._get(f"/api/v1/prospects?tenantId={tenant_id}&stage={stage}&limit={limit}")

    def get_dataset(self, dataset_id: str, **params: Any) -> Any:
        query = "&".join(f"{k}={v}" for k, v in params.items())
        return self._get(f"/api/v1/data/{dataset_id}?{query}" if query else f"/api/v1/data/{dataset_id}")

    def get_agent_context(self, tenant_id: str) -> Any:
        return self._get(f"/api/v1/agent-context?tenantId={tenant_id}")

    def list_agent_runs(
        self,
        tenant_id: str,
        *,
        limit: int = 200,
        subject_type: str | None = None,
        subject_id: str | None = None,
    ) -> Any:
        query = f"tenantId={tenant_id}&limit={limit}"
        if subject_type:
            query += f"&subjectType={subject_type}"
        if subject_id:
            query += f"&subjectId={subject_id}"
        return self._get(f"/api/v1/agent-runs?{query}")

    # Mediated writes — these open platform workflows, they never write the
    # ledger and their output still passes the human approval gates.
    def request_screening(self, prospect_id: str) -> Any:
        return self._post(f"/api/v1/prospects/{prospect_id}/screen")

    def request_ic_review(self, prospect_id: str) -> Any:
        return self._post(f"/api/v1/prospects/{prospect_id}/ic-review")

    def open_dd_evidence(self, prospect_id: str, workstream: str, summary: str) -> Any:
        return self._post(
            f"/api/v1/prospects/{prospect_id}/dd-evidence",
            {"workstream": workstream, "summary": summary},
        )

    # agent_run (V33) — F4's audit spine: every production run records before it
    # starts and finishes when it lands. A replayed run_key returns the existing
    # row, so a retried trigger reads back instead of duplicating.
    def record_run(
        self,
        *,
        tenant_id: str,
        workflow: str,
        run_key: str,
        subject_type: str,
        subject_id: str,
        input: Any,
        models: Any,
        thresholds: Any = None,
        request_ids: Any = None,
    ) -> Any:
        return self._post(
            "/api/v1/agent-runs",
            {
                "tenantId": tenant_id,
                "workflow": workflow,
                "runKey": run_key,
                "subjectType": subject_type,
                "subjectId": subject_id,
                "input": input,
                "models": models,
                "thresholds": thresholds,
                "requestIds": request_ids,
            },
        )

    def finish_run(
        self,
        run_id: str,
        *,
        status: str,
        output: Any = None,
        verdict: Any = None,
        error: str | None = None,
    ) -> Any:
        return self._post(
            f"/api/v1/agent-runs/{run_id}/finish",
            {"status": status, "output": output, "verdict": verdict, "error": error},
        )

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
