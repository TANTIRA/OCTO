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
| `RUN_DEADLINE_S` | end-to-end budget per workflow run, default `100` (max `110`); past it the run is recorded `failed` and answers 504, inside the platform's 120 s timeout |
| `OCTO_AGENTS_SCREENING_DD_ENABLED` | feature flag for the first workflow (default off) |
| `OCTO_AGENTS_DD_ENABLED` | F3 parallel DD workstreams (default off) |
| `OCTO_AGENTS_IC_MEMO_ENABLED` | F5 IC memo drafting (default off) |
| `OCTO_AGENTS_LP_REPORT_ENABLED` | F8 LP report drafting (default off) |
| `OCTO_AGENTS_BRAIN_ENABLED` | F7 company-brain NL query (default off) |
| `OCTO_AGENTS_COMPLIANCE_ENABLED` | F9 compliance rationale (default off) |
| `OCTO_AGENTS_EQUITY_BRIDGE_ENABLED` | F6 equity-bridge quarterly analysis (default off) |
| `OCTO_AGENTS_CALIBRATION_ENABLED` | F12 verdict-vs-outcome calibration (default off) |
| `OCTO_AGENTS_DDQ_ENABLED` | F10 DDQ/RFP response drafting (default off) |
| `OCTO_AGENTS_OPERATING_REVIEW_ENABLED` | F13 operating-partner review (default off) |
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

- `POST /v1/workflows/compliance-rationale` — `{tenant_id, subject, as_of,
  outcomes}` → F9: the platform's engine has already evaluated the rules; the
  tool-free drafter narrates the outcomes for an approver, and jev's citation
  gate verifies every rule id, threshold and measured value matches exactly —
  a mis-cited draft is refused. Flag: `OCTO_AGENTS_COMPLIANCE_ENABLED`.

- `POST /v1/workflows/equity-bridge` — `{tenant_id, company, entry, exit,
  effects, change, method, *_currency}` → F6: the Investment Analyst's
  quarter-end equity-bridge. The platform computed the bridge
  (`analytics/Bridge.kt`, §4.3); the tool-free drafter narrates entry, exit
  and driver effects, and jev's citation gate verifies every figure matches —
  a mis-cited analysis is refused. Flag: `OCTO_AGENTS_EQUITY_BRIDGE_ENABLED`.

- `POST /v1/workflows/ddq-response` — `{tenant_id, subject, questions, facts}`
  → F10: the Fundraising persona answers an LP due-diligence questionnaire.
  A sealed narrator like lp-report — the firm facts arrive inline, the
  drafter runs with no tools, and a question the materials do not cover is
  answered "Not covered in the provided materials", never fabricated. Jev
  gates grounding, coverage and tone; a refusal keeps the draft on
  `agent_run`. No LP identities enter the prompt. Flag: `OCTO_AGENTS_DDQ_ENABLED`.

- `POST /v1/workflows/operating-review` — `{tenant_id, company, levers,
  metrics}` → F13: the Operating Partner reviews a portfolio company's
  operating trajectory. A sealed narrator over the supplied period metrics —
  the drafter runs with no tools and, per declared value-creation lever,
  states what the series shows and whether it is on/off track. Jev gates
  grounding, lever coverage, and that it stays analytical rather than
  prescriptive. Warm context (the fund's operating playbook) is allowed;
  a refusal keeps the draft on `agent_run`. Flag:
  `OCTO_AGENTS_OPERATING_REVIEW_ENABLED`.

- `POST /v1/workflows/calibration` — `{tenant_id, limit}` → F12: joins each
  finished run's verdict against its `human_outcome` (recorded via the
  platform's `POST /api/v1/agent-runs/{id}/outcome` — `decision` of
  `accepted` agrees, `rejected`/`overridden` disagrees). Returns per-workflow
  agreement, the disagreement queue, and eval-ready cases
  (`expect_ship` = whether the artifact should have shipped), each referencing
  its run by `run_id`. Output stays under the platform's 32 KB run-output cap:
  lists are capped and `truncated` is set, with exact `*_total` counts. Deterministic —
  no model calls. Flag: `OCTO_AGENTS_CALIBRATION_ENABLED`.

## Warm context (F11)

A tenant's standing brief — fund thesis, DD playbook, preferred tone — lives
in `tenant_setting` under `agents.warm_context`, admin-written via
`PUT /api/v1/admin/tenants/{id}/settings`. The investigative workflows
(`screening-dd`, `due-diligence`, `ic-memo`, `company-brain`) fetch it through
`GET /api/v1/agent-context` and prepend it to the drafter's system prompt. The
sealed narrators (`lp-report`, `compliance-rationale`, `equity-bridge`) stay
fact-scoped on purpose: their citation gates verify output against the
supplied facts only, so outside text would read as unsupported.

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
