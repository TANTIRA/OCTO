# OCTO agents image (ADR-0005) — the Python sidecar. Build context is agents/.
# Build from the repo root:  docker build -t "$REGISTRY_URL/octo-agents:$AGENTS_IMAGE_TAG" agents
#
# Dependencies resolve from the committed uv.lock, never from pyproject's `>=` ranges. The previous
# `pip install .` re-resolved on every build, so two builds of the same commit could ship different
# dependency trees and the lockfile was decorative.
#
# Base image and the uv binary are pinned by version + digest (infra/README.md guardrails).
# Bump both together:
#   docker buildx imagetools inspect python:3.12.14-slim --format '{{.Manifest.Digest}}'
#   docker buildx imagetools inspect ghcr.io/astral-sh/uv:0.12.21 --format '{{.Manifest.Digest}}'

FROM python:3.12.14-slim@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f

# The resolver the lockfile was generated with, pinned so a newer uv cannot re-interpret it.
COPY --from=ghcr.io/astral-sh/uv:0.12.21@sha256:a7aed3216253ee804de3e2d8afa5073baa1a177335345d43845cd4165e43b711 /uv /uvx /bin/

# UV_PYTHON_DOWNLOADS: use the base image's interpreter, never fetch a managed one.
# PATH: the venv's console scripts (uvicorn) resolve without an activation step.
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    PATH="/app/.venv/bin:$PATH"

WORKDIR /app

# Dependency layer first — it invalidates only when pyproject.toml or uv.lock changes, not on every
# source edit. --frozen matches CI's `uv sync --frozen`: the lock is authoritative and is never
# silently re-resolved here.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY octo_agents ./octo_agents
COPY models.yaml ./
RUN uv sync --frozen --no-dev --no-editable

RUN groupadd --system --gid 1001 octo \
    && useradd --system --uid 1001 --gid octo --shell /usr/sbin/nologin octo \
    && chown -R octo:octo /app

USER octo

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=4)"

CMD ["uvicorn", "octo_agents.server:app", "--host", "0.0.0.0", "--port", "8080"]
