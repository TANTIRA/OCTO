"""Environment configuration for the agent sidecar.

Every setting maps to an env var of the same name (upper-cased). Secrets only
ever arrive through env — never through request bodies or the tool surface.
"""

from functools import lru_cache
from pathlib import Path
from urllib.parse import urlparse

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    # OpenRouter — the only model provider (ADR-0005 data path). Optional at
    # construction so the app boots and flag-off / model-free endpoints answer;
    # the endpoints that need it return 503 when it is blank (not a 500 on every
    # call, backlog #324).
    openrouter_api_key: str = ""
    openrouter_decisions_endpoint: str = "https://openrouter.ai/api/alpha/decisions"
    openrouter_chat_endpoint: str = "https://openrouter.ai/api/v1"

    # Kotlin api — the sidecar's only tool surface. The service principal JWT is
    # minted ops-side with a least-privilege role (tasks and drafts only).
    # The default is the internal compose-network hop, which is plaintext by
    # design (TLS terminates at Traefik) — but shipping a bearer token over
    # http to a mispointed base URL must fail closed, so plaintext needs the
    # explicit opt-in below (backlog #345).
    octo_api_base_url: str = "http://api:8080"
    octo_agent_token: str = ""
    octo_agents_insecure_http: bool = False

    # Bearer token the Kotlin platform presents when it calls this sidecar.
    octo_agents_token: str = ""

    # Approved-model registry (models.yaml) — versioned in this repo.
    model_registry_path: str = str(
        Path(__file__).resolve().parent.parent / "models.yaml"
    )

    # First workflow gate: Investment Screening & DD, tasks-only per ADR-0005.
    octo_agents_screening_dd_enabled: bool = False
    # Parallel DD workstreams (F3) — subagent orchestration, evidence tasks only.
    octo_agents_dd_enabled: bool = False
    # IC memo drafting (F5) — judged draft, ic-review task only when the gate passes.
    octo_agents_ic_memo_enabled: bool = False
    # LP report drafting (F8) — narrates a report job's inline facts, release-gated.
    octo_agents_lp_report_enabled: bool = False
    # Company-brain NL query (F7) — judged answers over the pipeline records.
    octo_agents_brain_enabled: bool = False
    # Compliance rationale (F9) — narrates engine outcomes, citation-gated.
    octo_agents_compliance_enabled: bool = False
    # Equity-bridge quarterly analysis (F6) — narrates the computed bridge.
    octo_agents_equity_bridge_enabled: bool = False
    # Calibration (F12) — verdict-vs-human-outcome analysis over the run spine.
    octo_agents_calibration_enabled: bool = False
    # DDQ/RFP response drafting (F10) — sealed narrator over supplied firm facts.
    octo_agents_ddq_enabled: bool = False
    # Operating-partner review (F13) — sealed narrator over a company's metrics.
    octo_agents_operating_review_enabled: bool = False

    request_timeout_s: float = 60.0

    @model_validator(mode="after")
    def _plaintext_api_url_requires_opt_in(self) -> "Settings":
        if (
            urlparse(self.octo_api_base_url).scheme == "http"
            and not self.octo_agents_insecure_http
        ):
            raise ValueError(
                "octo_api_base_url is plaintext http — the api bearer token would "
                "travel in cleartext. Set OCTO_AGENTS_INSECURE_HTTP=true only on "
                "the internal compose network (TLS terminates at Traefik)."
            )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
