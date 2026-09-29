"""F11 — warm context. The tenant's standing brief (`tenant_setting` key
`agents.warm_context`, admin-managed) is prepended to every drafter prompt so a
fund's thesis, DD playbook and tone travel with each run instead of being
re-derived per call. Fetched through the Kotlin edge like every other read —
the sidecar never touches settings directly. An absent setting is a no-op.
"""

from ..api_client import OctoApiClient


def warm_prompt(api: OctoApiClient, tenant_id: str, base: str) -> str:
    """Append the tenant's warm-context brief to [base] when one is set."""
    context = (api.get_agent_context(tenant_id) or {}).get("warmContext")
    if not context or not str(context).strip():
        return base
    return f"{base}\n\nTenant context — the firm's standing brief, treat as governing:\n{context}"
