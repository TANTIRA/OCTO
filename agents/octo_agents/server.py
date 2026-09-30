"""HTTP surface — the Kotlin platform is the only client (ADR-0005).

The service sits on dokploy-network with no Traefik route. Every run endpoint
requires the shared bearer (OCTO_AGENTS_TOKEN) plus its per-workflow feature
flag; /healthz is open for the compose healthcheck only.
"""

import hmac
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import date
from functools import lru_cache
from threading import Lock
from typing import Any, TypeVar
from uuid import uuid4

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field

from .api_client import OctoApiClient
from .chat import drafter_model
from .config import Settings, get_settings
from .judge import JudgeClient
from .registry import ApprovedModelRegistry
from .workflows.calibration import run_calibration
from .workflows.company_brain import run_company_brain
from .workflows.compliance_rationale import run_compliance_rationale
from .workflows.ddq_response import run_ddq_response
from .workflows.due_diligence import run_due_diligence
from .workflows.equity_bridge import run_equity_bridge
from .workflows.ic_memo import run_ic_memo
from .workflows.lp_report import run_lp_report
from .workflows.operating_review import run_operating_review
from .workflows.screening_dd import run_screening_dd


@asynccontextmanager
async def _lifespan(_app: FastAPI) -> AsyncIterator[None]:
    yield
    close_clients()


# No docs surface: the schema leaks the endpoint map to anyone who can reach
# the port — /docs, /redoc and /openapi.json stay off (backlog #345).
app = FastAPI(
    title="octo-agents",
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=_lifespan,
)
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
    if credentials is None or not hmac.compare_digest(
        credentials.credentials, settings.octo_agents_token
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)


# The registry parses models.yaml on construction — doing it per request added
# a file read + YAML parse to every workflow call. Cache per path like the
# client factories below: models.yaml is versioned in the repo, so a change
# arrives with a redeploy anyway, and tests clear the cache explicitly.
@lru_cache(maxsize=4)
def _registry_at(path: str) -> ApprovedModelRegistry:
    return ApprovedModelRegistry(path)


def _registry(settings: Settings) -> ApprovedModelRegistry:
    return _registry_at(settings.model_registry_path)


# One client per (kind, config) tuple, shared for the app's lifetime — each
# wraps a long-lived httpx.Client, so building fresh per call leaked
# connections and file descriptors under sustained traffic (backlog #325).
# The credentials are the sidecar's own service config, never the caller's
# bearer, so sharing one client shares a pool, not someone else's identity.
# A config change yields a new key; every client is closed on shutdown.
_clients: dict[tuple[Any, ...], JudgeClient | OctoApiClient] = {}
_clients_lock = Lock()
_C = TypeVar("_C", JudgeClient, OctoApiClient)


def _shared(key: tuple[Any, ...], build: Callable[[], _C]) -> _C:
    with _clients_lock:  # sync endpoints run on a threadpool
        client = _clients.get(key)
        if client is None:
            client = _clients[key] = build()
        return client  # type: ignore[return-value]


def close_clients() -> None:
    with _clients_lock:
        for client in _clients.values():
            client.close()
        _clients.clear()


def _judge_client(
    endpoint: str, api_key: str, model: str, timeout: float
) -> JudgeClient:
    return _shared(
        ("judge", endpoint, api_key, model, timeout),
        lambda: JudgeClient(
            endpoint=endpoint, api_key=api_key, model=model, timeout_s=timeout
        ),
    )


def _api_client(base_url: str, token: str, timeout: float) -> OctoApiClient:
    return _shared(
        ("api", base_url, token, timeout),
        lambda: OctoApiClient(base_url, token, timeout_s=timeout),
    )


def _judge(settings: Settings, registry: ApprovedModelRegistry) -> JudgeClient:
    if not settings.openrouter_api_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="OPENROUTER_API_KEY is not configured",
        )
    model = registry.resolve("judge", confidential=True)
    return _judge_client(
        settings.openrouter_decisions_endpoint,
        settings.openrouter_api_key,
        model.model_id,
        settings.request_timeout_s,
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
    if not settings.octo_agent_token:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="OCTO_AGENT_TOKEN is not configured",
        )
    return _api_client(
        settings.octo_api_base_url,
        settings.octo_agent_token,
        settings.request_timeout_s,
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


class BrainQueryRequest(BaseModel):
    tenant_id: str
    question: str
    run_key: str | None = None


@app.post("/v1/workflows/company-brain")
def company_brain(
    body: BrainQueryRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_brain_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="company-brain workflow is feature-flagged off",
        )
    registry = _registry(settings)
    result = run_company_brain(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        tenant_id=body.tenant_id,
        question=body.question,
        run_key=body.run_key or str(uuid4()),
        models=_models(registry),
    )
    return result.model_dump()


class CalibrationRequest(BaseModel):
    tenant_id: str
    run_key: str | None = None
    # Matches the api's agent-runs page bound (limit in 1..200) — anything
    # higher would 400 upstream anyway, so reject it here first.
    limit: int = Field(default=200, ge=1, le=200)


@app.post("/v1/workflows/calibration")
def calibration(
    body: CalibrationRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_calibration_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="calibration workflow is feature-flagged off",
        )
    # Deterministic analysis — no model is invoked, so lineage records none.
    result = run_calibration(
        api=_api(settings),
        tenant_id=body.tenant_id,
        run_key=body.run_key or str(uuid4()),
        models={},
        limit=body.limit,
    )
    return result.model_dump()


class ComplianceRationaleRequest(BaseModel):
    tenant_id: str
    subject: str
    # A real ISO date, not an opaque string — a malformed as_of used to crash
    # the workflow deep inside the run instead of answering 422 at the edge.
    as_of: date
    outcomes: list[dict[str, Any]] = []
    run_key: str | None = None


@app.post("/v1/workflows/compliance-rationale")
def compliance_rationale(
    body: ComplianceRationaleRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_compliance_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="compliance-rationale workflow is feature-flagged off",
        )
    registry = _registry(settings)
    result = run_compliance_rationale(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        tenant_id=body.tenant_id,
        subject=body.subject,
        as_of=body.as_of.isoformat(),
        outcomes=body.outcomes,
        run_key=body.run_key or str(uuid4()),
        models=_models(registry),
    )
    return result.model_dump()


class EquityBridgeRequest(BaseModel):
    tenant_id: str
    company: str
    entry: dict[str, Any] = {}
    exit: dict[str, Any] = {}
    effects: dict[str, Any] = {}
    change: str
    method: str
    local_currency: str
    reporting_currency: str
    run_key: str | None = None


@app.post("/v1/workflows/equity-bridge")
def equity_bridge(
    body: EquityBridgeRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_equity_bridge_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="equity-bridge workflow is feature-flagged off",
        )
    registry = _registry(settings)
    result = run_equity_bridge(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        tenant_id=body.tenant_id,
        company=body.company,
        entry=body.entry,
        exit=body.exit,
        effects=body.effects,
        change=body.change,
        method=body.method,
        local_currency=body.local_currency,
        reporting_currency=body.reporting_currency,
        run_key=body.run_key or str(uuid4()),
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


class DdqResponseRequest(BaseModel):
    tenant_id: str
    subject: str
    questions: list[str] = []
    facts: dict[str, Any] = {}
    run_key: str | None = None


@app.post("/v1/workflows/ddq-response")
def ddq_response(
    body: DdqResponseRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_ddq_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="ddq-response workflow is feature-flagged off",
        )
    registry = _registry(settings)
    result = run_ddq_response(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        tenant_id=body.tenant_id,
        subject=body.subject,
        questions=body.questions,
        facts=body.facts,
        run_key=body.run_key or str(uuid4()),
        models=_models(registry),
    )
    return result.model_dump()


class OperatingReviewRequest(BaseModel):
    tenant_id: str
    company: str
    levers: list[str] = []
    metrics: dict[str, Any] = {}
    run_key: str | None = None


@app.post("/v1/workflows/operating-review")
def operating_review(
    body: OperatingReviewRequest,
    _: None = Depends(require_caller),
    settings: Settings = Depends(get_settings),
) -> dict[str, Any]:
    if not settings.octo_agents_operating_review_enabled:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="operating-review workflow is feature-flagged off",
        )
    registry = _registry(settings)
    result = run_operating_review(
        agent_model=drafter_model(settings, registry),
        judge=_judge(settings, registry),
        api=_api(settings),
        tenant_id=body.tenant_id,
        company=body.company,
        levers=body.levers,
        metrics=body.metrics,
        run_key=body.run_key or str(uuid4()),
        models=_models(registry),
    )
    return result.model_dump()
