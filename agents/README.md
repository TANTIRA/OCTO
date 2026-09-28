# octo-agents — Python agent sidecar

AIP workflow orchestration per **ADR-0005**: the Kotlin platform is this
service's only client and its only tool surface.

## Boundaries

- **No public route.** Runs on `dokploy-network` only, like `octo-neo4j-db`.
- **No direct database access.** Agents reach platform data only through the
  Kotlin api (`OCTO_API_BASE_URL`) as a least-privilege service principal —
  the same JWT + RBAC surface a human uses.
- **Mediated writes only.** The drafter's tool list is read-only. Writes
  (`POST /prospects/{id}/screen` → workflow task, `POST /reports` → draft
  through the approval gate) happen in workflow code after the judge gate.
  A model can propose; it can never open a task, file a draft, approve, or
  touch the ledger.
- **ZDR fail-closed.** `models.yaml` is the approved-model registry. A role
  resolves only to a listed model; confidential payloads to a non-`zdr` model
  raise before a request exists. Requests additionally send OpenRouter
  `provider {zdr: true, allow_fallbacks: false}` so non-ZDR routes are
  rejected endpoint-side too. `models.yaml` mirrors the platform registry
  (`modules/control-panel/.../judgment/ModelRegistry.kt`) — approving or
  retiring a model is a governance action, so shared model ids (e.g.
  `typesafe/jev-1.13` as judge) must be updated in lockstep in both.

## Model roles

| Role | Model | Client |
| --- | --- | --- |
| `judge` | `typesafe/jev-1.13` | decisions endpoint (`octo_agents/judge.py`) — typed noul/choice/score questions with calibrated probabilities; never drafts |
| `drafter` | `deepseek/deepseek-v4.1-flash` | `ChatOpenRouter` (`octo_agents/chat.py`) — deepagent planning, tool use, prose |

## Env

| Var | Purpose |
| --- | --- |
| `OPENROUTER_API_KEY` | model provider key (required) |
| `OCTO_API_BASE_URL` | Kotlin api base, default `http://api:8080` |
| `OCTO_AGENT_TOKEN` | service-principal JWT for api calls (tasks+drafts scope) |
| `OCTO_AGENTS_TOKEN` | bearer the platform presents when calling this service |
| `OCTO_AGENTS_SCREENING_DD_ENABLED` | feature flag for the first workflow (default off) |
| `MODEL_REGISTRY_PATH` | registry override, default `./models.yaml` |

## API

- `GET /healthz` — liveness for the compose healthcheck
- `POST /v1/workflows/screening-dd` — `{prospect_id}` → runs the screening
  pipeline, returns `{memo, verdict{proceed_probability, confidence,
  judge_lineage}, screening_requested}`

## Evals

`evals/` holds normal / edge / injection cases mirroring #52. Run:

```bash
uv run evals/run_evals.py
```

Exits non-zero below the configured threshold — wire into CI before any
workflow goes live.

## Local dev

```bash
cd agents
uv sync
uv run pytest
uv run uvicorn octo_agents.server:app --port 8080
```
