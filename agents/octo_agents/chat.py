"""Chat-model factory for the drafter role.

The judge is never built here — jev only answers typed questions through
judge.py. The drafter drives deepagent planning and prose, and every request
carries provider {zdr: true, allow_fallbacks: false} so OpenRouter rejects any
route that is not zero-data-retention.
"""

from typing import Any

from langchain_core.callbacks import (
    AsyncCallbackManagerForLLMRun,
    CallbackManagerForLLMRun,
)
from langchain_core.messages import BaseMessage
from langchain_core.outputs import ChatResult
from langchain_openrouter import ChatOpenRouter

from .config import Settings
from .deadline import bind_drafter_http_client, drafter_request_overrides
from .registry import ApprovedModelRegistry

# OpenRouter provider routing — zero-data-retention endpoints only, no fallbacks.
_ZDR_PROVIDER = {"zdr": True, "allow_fallbacks": False}


class _DrafterChat(ChatOpenRouter):
    """ChatOpenRouter whose every attempt is bounded by the run deadline.

    The SDK's own retry window (`max_retries * 150s`) is replaced per call.
    The HTTP transport installed by `bind_drafter_http_client` is what refuses
    a send after the deadline, including a retry the SDK already scheduled.
    """

    def _with_deadline(self, kwargs: dict[str, Any]) -> dict[str, Any]:
        return {**kwargs, **drafter_request_overrides(self.request_timeout)}

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        return super()._generate(
            messages,
            stop=stop,
            run_manager=run_manager,
            **self._with_deadline(kwargs),
        )

    def _stream(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> Any:
        return super()._stream(
            messages,
            stop=stop,
            run_manager=run_manager,
            **self._with_deadline(kwargs),
        )

    async def _agenerate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: AsyncCallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        return await super()._agenerate(
            messages,
            stop=stop,
            run_manager=run_manager,
            **self._with_deadline(kwargs),
        )

    async def _astream(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: AsyncCallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> Any:
        async for chunk in super()._astream(
            messages,
            stop=stop,
            run_manager=run_manager,
            **self._with_deadline(kwargs),
        ):
            yield chunk


def drafter_model(
    settings: Settings, registry: ApprovedModelRegistry
) -> ChatOpenRouter:
    model = registry.resolve("drafter", confidential=True)
    chat = _DrafterChat(
        model=model.model_id,
        temperature=0,
        max_retries=2,
        # The retrying client must also bound each attempt, or a hung upstream
        # holds the run open for the full deepagent budget (backlog #326).
        # ChatOpenRouter.request_timeout is milliseconds (SDK timeout_ms) —
        # passing seconds bounded every drafter call at ~60 ms.
        # Under a run deadline those retries are retargeted at the time left
        # (`drafter_request_overrides`), so they cannot bill past it (#554).
        request_timeout=int(settings.request_timeout_s * 1000),
        openrouter_api_key=settings.openrouter_api_key,
        openrouter_api_base=settings.openrouter_chat_endpoint,
        openrouter_provider=_ZDR_PROVIDER,
    )
    bind_drafter_http_client(chat)
    return chat
