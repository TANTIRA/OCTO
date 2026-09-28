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
| `OCTO_AGENTS_DD_ENABLED` | F3 parallel DD workstreams (default off) |
| `OCTO_AGENTS_IC_MEMO_ENABLED` | F5 IC memo drafting (default off) |
| `OCTO_AGENTS_LP_REPORT_ENABLED` | F8 LP report drafting (default off) |
| `OCTO_AGENTS_BRAIN_ENABLED` | F7 company-brain NL query (default off) |
| `MODEL_REGISTRY_PATH` | registry override, default `./models.yaml` |

## API

- `GET /healthz` — liveness for the compose healthcheck
- `POST /v1/workflows/screening-dd` — `{prospect_id}` → runs the screening
  pipeline, returns `{status, memo, preflight, retrieval, verdict, screening_requested}`.
  Two jev gates run before the drafter: a `noul` pre-flight refuses records with
  no substance (no drafter call, `status: "refused"`), then per-event `score`
  questions admit only relevant chunks into the memo context.

- `POST /v1/workflows/due-diligence` — `{prospect_id}` → runs the parallel DD
  pipeline (F3): four read-only subagents (market, financial, legal,
  operational) with isolated contexts, jev bands each stream
  low/medium/high/blocker, and high-or-blocker streams mint one
  `EVIDENCE_REQUEST` task each via `POST /prospects/{id}/dd-evidence` on the
  platform — idempotent per workstream. Flag: `OCTO_AGENTS_DD_ENABLED`.

- `POST /v1/workflows/ic-memo` — `{prospect_id}` → F5: drafts the IC memo on
  the admitted evidence, then jev gates completeness/thesis/evidence. A pass
  opens the prospect's `ic-review` approval task through the platform — but
  only while it stands at ic-review; otherwise the memo stays a judged draft
  on `agent_run`. Flag: `OCTO_AGENTS_IC_MEMO_ENABLED`.

- `POST /v1/workflows/lp-report` — `{job_id, position_source_*, measures,
  parameters}` → F8: the report runner calls this for `lp-report` jobs. The
  drafter runs with no tools and narrates only the job's inline facts; jev
  refuses drafts it cannot support, which errors the job. A `done` job is
  still sealed until `/release` approval. Flag: `OCTO_AGENTS_LP_REPORT_ENABLED`.

- `POST /v1/workflows/company-brain` — `{tenant_id, question}` → F7: jev
  pre-gates whether the platform's records could answer, the drafter answers
  through read tools plus `list_pipeline(stage)`, and a second jev gate
  refuses drafts it cannot support — the caller sees an honest note, never a
  confident hallucination. Flag: `OCTO_AGENTS_BRAIN_ENABLED`.

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
