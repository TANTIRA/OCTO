"""Chat-model factory for the drafter role.

The judge is never built here — jev only answers typed questions through
judge.py. The drafter drives deepagent planning and prose, and every request
carries provider {zdr: true, allow_fallbacks: false} so OpenRouter rejects any
route that is not zero-data-retention.
"""

from langchain_openrouter import ChatOpenRouter

from .config import Settings
from .registry import ApprovedModelRegistry

# OpenRouter provider routing — zero-data-retention endpoints only, no fallbacks.
_ZDR_PROVIDER = {"zdr": True, "allow_fallbacks": False}


def drafter_model(
    settings: Settings, registry: ApprovedModelRegistry
) -> ChatOpenRouter:
    model = registry.resolve("drafter", confidential=True)
    return ChatOpenRouter(
        model=model.model_id,
        temperature=0,
        max_retries=2,
        # The retrying client must also bound each attempt, or a hung upstream
        # holds the run open for the full deepagent budget (backlog #326).
        # ChatOpenRouter.request_timeout is milliseconds (SDK timeout_ms) —
        # passing seconds bounded every drafter call at ~60 ms.
        request_timeout=int(settings.request_timeout_s * 1000),
        openrouter_api_key=settings.openrouter_api_key,
        openrouter_api_base=settings.openrouter_chat_endpoint,
        openrouter_provider=_ZDR_PROVIDER,
    )
