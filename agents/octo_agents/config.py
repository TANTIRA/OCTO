"""Environment configuration for the agent sidecar.

Every setting maps to an env var of the same name (upper-cased). Secrets only
ever arrive through env — never through request bodies or the tool surface.
"""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    # OpenRouter — the only model provider (ADR-0005 data path).
    openrouter_api_key: str
    openrouter_decisions_endpoint: str = "https://openrouter.ai/api/alpha/decisions"
    openrouter_chat_endpoint: str = "https://openrouter.ai/api/v1"

    # Kotlin api — the sidecar's only tool surface. The service principal JWT is
    # minted ops-side with a least-privilege role (tasks and drafts only).
    octo_api_base_url: str = "http://api:8080"
    octo_agent_token: str = ""

    # Bearer token the Kotlin platform presents when it calls this sidecar.
    octo_agents_token: str = ""

    # Approved-model registry (models.yaml) — versioned in this repo.
    model_registry_path: str = str(Path(__file__).resolve().parent.parent / "models.yaml")

    # First workflow gate: Investment Screening & DD, tasks-only per ADR-0005.
    octo_agents_screening_dd_enabled: bool = False
    # Parallel DD workstreams (F3) — subagent orchestration, evidence tasks only.
    octo_agents_dd_enabled: bool = False

    request_timeout_s: float = 60.0


@lru_cache
def get_settings() -> Settings:
    return Settings()
