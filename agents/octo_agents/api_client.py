"""Kotlin api client — the sidecar's only tool surface (ADR-0005 tool boundary).

Agents get read endpoints plus mediated writes that open workflow tasks inside
the platform (screening, IC review, DD evidence) and record their own agent_run.
There is no direct database access, no ledger write and no approval path here
by construction.
"""

from collections.abc import Mapping
from typing import Any
from urllib.parse import quote

import httpx

from . import deadline
from .retry import send_with_retry


def _q(value: Any) -> str:
    """Path-segment encoding — a caller-supplied id must never be able to break
    out of its segment (../, ?) or smuggle extra path/query shape upstream."""
    return quote(str(value), safe="")


class OctoApiError(RuntimeError):
    def __init__(self, status_code: int, body: str) -> None:
        msg = f"octo api call failed with status {status_code}"
        if body:
            msg += f": {body}"
        super().__init__(msg)
        self.status_code = status_code
        self.body = body


class OctoApiClient:
    def __init__(
        self,
        base_url: str,
        token: str,
        *,
        timeout_s: float = 60.0,
        retries: int = 2,
        backoff_s: float = 0.5,
        client: httpx.Client | None = None,
    ) -> None:
        if not token:
            raise ValueError("octo_agent_token is required for api calls")
        self._retries = retries
        self._backoff_s = backoff_s
        # An injected client still gets base_url + auth — injection swaps the
        # transport (tests), never the credentials contract.
        self._client = client or httpx.Client(timeout=timeout_s)
        self._client.base_url = base_url.rstrip("/")
        self._client.headers["Authorization"] = f"Bearer {token}"

    def close(self) -> None:
        self._client.close()

    def _get(self, path: str, params: Mapping[str, Any] | None = None) -> Any:
        r = send_with_retry(
            lambda: self._client.get(
                path, params=params, timeout=deadline.http_timeout(self._client)
            ),
            retries=self._retries,
            backoff_s=self._backoff_s,
        )
        if r.status_code not in range(200, 300):
            raise OctoApiError(r.status_code, r.text[:512])
        return r.json()

    def _post(
        self,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        idempotent: bool = False,
    ) -> Any:
        """Most POSTs open platform workflows, so a resend could duplicate one —
        only endpoints the server dedupes pass idempotent=True (see retry.py)."""
        r = send_with_retry(
            lambda: self._client.post(
                path, json=body or {}, timeout=deadline.http_timeout(self._client)
            ),
            retries=self._retries,
            backoff_s=self._backoff_s,
            idempotent=idempotent,
        )
        if r.status_code not in range(200, 300):
            raise OctoApiError(r.status_code, r.text[:512])
        return r.json() if r.text else {}

    # Reads — same JWT + RBAC surface a human service principal would use.
    def get_prospect(self, prospect_id: str) -> Any:
        return self._get(f"/api/v1/prospects/{_q(prospect_id)}")

    def list_prospect_events(self, prospect_id: str) -> Any:
        return self._get(f"/api/v1/prospects/{_q(prospect_id)}/events")

    def get_asset(self, asset_id: str) -> Any:
        return self._get(f"/api/v1/assets/{_q(asset_id)}")

    def list_pipeline(
        self,
        tenant_id: str,
        stage: str,
        limit: int = 50,
        offset: int = 0,
    ) -> Any:
        """One page of the stage's pipeline — the api caps a page and the caller
        decides whether to walk `offset` further. A truncated page is an honest
        page, not a complete list."""
        return self._get(
            "/api/v1/prospects",
            params={
                "tenantId": tenant_id,
                "stage": stage,
                "limit": limit,
                "offset": offset,
            },
        )

    def get_agent_context(self, tenant_id: str) -> Any:
        return self._get("/api/v1/agent-context", params={"tenantId": tenant_id})

    def list_agent_runs(self, tenant_id: str, *, limit: int = 200) -> Any:
        return self._get(
            "/api/v1/agent-runs", params={"tenantId": tenant_id, "limit": limit}
        )

    # Mediated writes — these open platform workflows, they never write the
    # ledger and their output still passes the human approval gates.
    def request_screening(self, prospect_id: str) -> Any:
        return self._post(f"/api/v1/prospects/{_q(prospect_id)}/screen")

    def request_ic_review(self, prospect_id: str) -> Any:
        return self._post(f"/api/v1/prospects/{_q(prospect_id)}/ic-review")

    def open_dd_evidence(self, prospect_id: str, workstream: str, summary: str) -> Any:
        return self._post(
            f"/api/v1/prospects/{_q(prospect_id)}/dd-evidence",
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
            idempotent=True,  # the api replays an existing run_key's row
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
            f"/api/v1/agent-runs/{_q(run_id)}/finish",
            {"status": status, "output": output, "verdict": verdict, "error": error},
            idempotent=True,  # first finish wins; a replay answers 409, never rewrites
        )
