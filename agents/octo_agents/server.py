"""HTTP surface — the Kotlin platform is the only client (ADR-0005).

The service sits on dokploy-network with no Traefik route. Every run endpoint
requires the shared bearer (OCTO_AGENTS_TOKEN) plus its per-workflow feature
flag; /healthz is open for the compose healthcheck only.
"""

from typing import Any
from uuid import uuid4

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel

from .api_client import OctoApiClient
from .chat import drafter_model
from .config import Settings, get_settings
from .judge import JudgeClient
from .registry import ApprovedModelRegistry
from .workflows.due_diligence import run_due_diligence
from .workflows.ic_memo import run_ic_memo
from .workflows.lp_report import run_lp_report
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


def _models(registry: ApprovedModelRegistry) -> dict[str, str]:
    """Model ids for the run's lineage — resolved through the same registry the
    clients use, so the recorded pair is the pair that actually ran."""
    return {
        "drafter": registry.resolve("drafter", confidential=True).model_id,
        "judge": registry.resolve("judge", confidential=True).model_id,
    }


def _api(settings: Settings) -> OctoApiClient:
    return OctoApiClient(
        settings.octo_api_base_url,
        settings.octo_agent_token,
        timeout_s=settings.request_timeout_s,
    )


class ScreeningDdRequest(BaseModel):
    prospect_id: str
    tenant_id: str
    # Caller-supplied dedupe key — Kotlin mints one per trigger; absent here a
    # direct caller gets a fresh run each post.
    run_key: str | None = None


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
    result = run_screening_dd(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        prospect_id=body.prospect_id,
        tenant_id=body.tenant_id,
        run_key=body.run_key or str(uuid4()),
        models=_models(registry),
    )
    return result.model_dump()


class DueDiligenceRequest(BaseModel):
    prospect_id: str
    tenant_id: str
    run_key: str | None = None


@app.post("/v1/workflows/due-diligence")
def due_diligence(
    body: DueDiligenceRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_dd_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="due-diligence workflow is feature-flagged off",
        )
    registry = _registry(settings)
    result = run_due_diligence(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        prospect_id=body.prospect_id,
        tenant_id=body.tenant_id,
        run_key=body.run_key or str(uuid4()),
        models=_models(registry),
    )
    return result.model_dump()


class LpReportRequest(BaseModel):
    job_id: str
    tenant_id: str
    position_source_type: str
    position_source_id: str
    measures: list[str] = []
    parameters: dict[str, Any] = {}
    run_key: str | None = None


@app.post("/v1/workflows/lp-report")
def lp_report(
    body: LpReportRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_lp_report_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="lp-report workflow is feature-flagged off",
        )
    registry = _registry(settings)
    result = run_lp_report(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        job_id=body.job_id,
        tenant_id=body.tenant_id,
        run_key=body.run_key or str(uuid4()),
        position_source_type=body.position_source_type,
        position_source_id=body.position_source_id,
        measures=body.measures,
        parameters=body.parameters,
        models=_models(registry),
    )
    return result.model_dump()


class IcMemoRequest(BaseModel):
    prospect_id: str
    tenant_id: str
    run_key: str | None = None


@app.post("/v1/workflows/ic-memo")
def ic_memo(
    body: IcMemoRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_ic_memo_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="ic-memo workflow is feature-flagged off",
        )
    registry = _registry(settings)
    result = run_ic_memo(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        prospect_id=body.prospect_id,
        tenant_id=body.tenant_id,
        run_key=body.run_key or str(uuid4()),
        models=_models(registry),
    )
    return result.model_dump()
