"""LangChain tool wrappers over the Kotlin api.

The drafter only ever receives the READ tools. Write paths (requesting a
screening, drafting a report) live in workflow code that runs after the judge
gate — a model can propose, it can never open a task or file a draft itself.
"""

from langchain_core.tools import tool

from .api_client import OctoApiClient


def read_tools(client: OctoApiClient) -> list:
    @tool
    def get_prospect(prospect_id: str) -> str:
        """Fetch a prospect's current state (identity, stage, source, tags)."""
        return str(client.get_prospect(prospect_id))

    @tool
    def list_prospect_events(prospect_id: str) -> str:
        """List the prospect's event history — stage transitions, checklist
        claims and source references in order."""
        return str(client.list_prospect_events(prospect_id))

    @tool
    def get_asset(asset_id: str) -> str:
        """Fetch an asset master record (type, identifiers/xrefs, region, tags)."""
        return str(client.get_asset(asset_id))

    return [get_prospect, list_prospect_events, get_asset]
