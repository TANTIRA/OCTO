"""Chat-model factory for the drafter role.

The judge is never built here — jev only answers typed questions through
judge.py. The drafter drives deepagent planning and prose, and every request
carries provider {zdr: true, allow_fallbacks: false} so OpenRouter rejects any
route that is not zero-data-retention.
"""

from typing import Any

import httpx
from langchain_openrouter import ChatOpenRouter

from .config import Settings
from .deadline import DeadlineTransport
from .registry import ApprovedModelRegistry

# OpenRouter provider routing — zero-data-retention endpoints only, no fallbacks.
_ZDR_PROVIDER = {"zdr": True, "allow_fallbacks": False}


def _sdk_client(
    settings: Settings, *, timeout_ms: int, max_retries: int
) -> Any:
    """The `openrouter` SDK client the model would build itself, but around a
    DeadlineTransport: an abandoned call's in-flight request is closed at the
    deadline and its retries fail instantly, so a timed-out run leaves nothing
    behind billing the provider (#554)."""
    import openrouter
    from openrouter.utils import BackoffStrategy, RetryConfig

    kwargs: dict[str, Any] = {
        "api_key": settings.openrouter_api_key,
        "client": httpx.Client(
            transport=DeadlineTransport(),
            timeout=settings.request_timeout_s,
            follow_redirects=True,
        ),
    }
    if settings.openrouter_chat_endpoint:
        kwargs["server_url"] = settings.openrouter_chat_endpoint
    kwargs["timeout_ms"] = timeout_ms
    if max_retries > 0:
        kwargs["retry_config"] = RetryConfig(
            strategy="backoff",
            backoff=BackoffStrategy(
                initial_interval=500,
                max_interval=60000,
                exponent=1.5,
                max_elapsed_time=max_retries * 150_000,
            ),
            retry_connection_errors=True,
        )
    return openrouter.OpenRouter(**kwargs)


def drafter_model(
    settings: Settings, registry: ApprovedModelRegistry
) -> ChatOpenRouter:
    model = registry.resolve("drafter", confidential=True)
    timeout_ms = int(settings.request_timeout_s * 1000)
    return ChatOpenRouter(
        model=model.model_id,
        temperature=0,
        max_retries=2,
        # The retrying client must also bound each attempt, or a hung upstream
        # holds the run open for the full deepagent budget (backlog #326).
        # ChatOpenRouter.request_timeout is milliseconds (SDK timeout_ms) —
        # passing seconds bounded every drafter call at ~60 ms.
        request_timeout=timeout_ms,
        openrouter_api_key=settings.openrouter_api_key,
        openrouter_api_base=settings.openrouter_chat_endpoint,
        openrouter_provider=_ZDR_PROVIDER,
        client=_sdk_client(settings, timeout_ms=timeout_ms, max_retries=2),
    )
