"""HTTP surface — the Kotlin platform is the only client (ADR-0005).

The service sits on dokploy-network with no Traefik route. Every run endpoint
requires the shared bearer (OCTO_AGENTS_TOKEN) plus its per-workflow feature
flag; /healthz is open for the compose healthcheck only.
"""

from typing import Any

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel

from .api_client import OctoApiClient
from .chat import drafter_model
from .config import Settings, get_settings
from .judge import JudgeClient
from .registry import ApprovedModelRegistry
from .workflows.screening_dd import run_screening_dd

app = FastAPI(title="octo-agents", version="0.1.0")
_bearer = HTTPBearer(auto_error=False)


def require_caller(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
) -> None:
    if not settings.octo_agents_token:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="OCTO_AGENTS_TOKEN is not configured",
        )
    if credentials is None or credentials.credentials != settings.octo_agents_token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)


def _registry(settings: Settings) -> ApprovedModelRegistry:
    return ApprovedModelRegistry(settings.model_registry_path)


def _judge(settings: Settings, registry: ApprovedModelRegistry) -> JudgeClient:
    model = registry.resolve("judge", confidential=True)
    return JudgeClient(
        endpoint=settings.openrouter_decisions_endpoint,
        api_key=settings.openrouter_api_key,
        model=model.model_id,
        timeout_s=settings.request_timeout_s,
    )


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


class ScreeningDdRequest(BaseModel):
    prospect_id: str


@app.post("/v1/workflows/screening-dd")
def screening_dd(
    body: ScreeningDdRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_screening_dd_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="screening-dd workflow is feature-flagged off",
        )
    registry = _registry(settings)
    api = OctoApiClient(
        settings.octo_api_base_url,
        settings.octo_agent_token,
        timeout_s=settings.request_timeout_s,
    )
    result = run_screening_dd(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=api,
        prospect_id=body.prospect_id,
    )
    return result.model_dump()
