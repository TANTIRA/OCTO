"""LangChain tool wrappers over the Kotlin api.

The drafter only ever receives the READ tools. Write paths (requesting a
screening, drafting a report) live in workflow code that runs after the judge
gate — a model can propose, it can never open a task or file a draft itself.

Every read is bound to the run's tenant (#318): the platform principal may
belong to several tenants, so the api answering a read is not proof the record
is this run's. A record whose `tenantId` is not the run's tenant never reaches
the model — the tool answers "not found", exactly like an unknown id, so ids
leak nothing across tenants.
"""

from typing import Any

from langchain_core.tools import tool

from .api_client import OctoApiClient, OctoApiError


class SubjectNotInTenantError(LookupError):
    """The requested subject is not visible in, or does not belong to, the
    requested tenant. Acting on it would book one tenant's run (and mediated
    writes) against another tenant's record (backlog #318)."""

    def __init__(self, subject: str, tenant_id: str) -> None:
        super().__init__(f"{subject} does not belong to tenant {tenant_id}")


def require_tenant(record: Any, subject: str, tenant_id: str) -> Any:
    """Returns `record` if its `tenantId` is `tenant_id`; raises otherwise.
    A record without a `tenantId` is refused — ownership is never assumed."""
    owner = record.get("tenantId") if isinstance(record, dict) else None
    if owner is None or str(owner).lower() != tenant_id.lower():
        raise SubjectNotInTenantError(subject, tenant_id)
    return record


def load_in_tenant(fetch: Any, subject: str, tenant_id: str) -> Any:
    """Calls `fetch()` and verifies the record belongs to `tenant_id`. The
    platform answers 404 for a record outside the principal's tenants, so a 404
    is the same refusal as a tenant mismatch; any other api error propagates."""
    try:
        record = fetch()
    except OctoApiError as e:
        if e.status_code == 404:
            raise SubjectNotInTenantError(subject, tenant_id) from e
        raise
    return require_tenant(record, subject, tenant_id)


def read_tools(client: OctoApiClient, tenant_id: str) -> list:
    def read(fetch: Any, subject: str) -> Any:
        return load_in_tenant(fetch, subject, tenant_id)

    @tool
    def get_prospect(prospect_id: str) -> str:
        """Fetch a prospect's current state (identity, stage, source, tags)."""
        try:
            return str(read(lambda: client.get_prospect(prospect_id), f"prospect {prospect_id}"))
        except SubjectNotInTenantError:
            return f"prospect {prospect_id} not found"

    @tool
    def list_prospect_events(prospect_id: str) -> str:
        """List the prospect's event history — stage transitions, checklist
        claims and source references in order."""
        # Events carry no tenantId of their own: they are the prospect's, so the
        # prospect's ownership is checked before the history is read.
        try:
            read(lambda: client.get_prospect(prospect_id), f"prospect {prospect_id}")
        except SubjectNotInTenantError:
            return f"prospect {prospect_id} not found"
        return str(client.list_prospect_events(prospect_id))

    @tool
    def get_asset(asset_id: str) -> str:
        """Fetch an asset master record (type, identifiers/xrefs, region, tags)."""
        try:
            return str(read(lambda: client.get_asset(asset_id), f"asset {asset_id}"))
        except SubjectNotInTenantError:
            return f"asset {asset_id} not found"

    return [get_prospect, list_prospect_events, get_asset]
