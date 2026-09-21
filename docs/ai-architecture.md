# AI Architecture — Reference Mapping

How the AIP architecture's twelve capability blocks map onto Mesta-Asset modules and controls. The reference is a general AI platform; Mesta-Asset adopts the capability model as fixed platform services behind governed module boundaries — not a self-serve platform layer.

## Block-by-block mapping

| # | AIP capability | Mesta-Asset equivalent | Module | Status |
| --- | --- | --- | --- | --- |
| 1 | Secure LLM integration, hosting, access — model catalog, moderation, PII detection, cache, quotas, BYOM | Approved-model registry; prompt/context firewall (untrusted doc handling); per-feature cost and quota limits; output citation checks | `control-panel` + platform config | Adopted |
| 2 | End-to-end observability | Prompt/retrieval/output/edit/approval lineage; eval results; cost, latency, drift dashboards; audit events | `control-panel` + observability stack | Adopted |
| 3 | Context engineering — contextual data, contextual logic, systems of action | Ontology-grounded retrieval (TypeDB graph + document store); metric DSL and TypeQL functions as contextual logic; ingestion pipelines + workflow triggers as systems of action | `ingestion`, `analytics`, `workflow` | Adopted |
| 4 | Ontology core — semantic, kinetic, dynamic | TypeDB schema (semantic), governed commands (kinetic), projections/derived views (dynamic); tool services = API surface exposed to agents | `ontology/` + `api` | Adopted |
| 5 | Human+AI applications | Deal Pipeline, Control Panel, dossier tabs — AI embedded in fixed product screens, not a separate app surface | product UI | Adopted |
| 6 | Security & governance — role/marking/purpose controls, approvals, checkpoints | RBAC+ABAC, data classification, approval-gated actions, tamper-evident audit, eval gates | `api` + `data-security-governance.md` | Adopted |
| 7 | Agent lifecycle — building, orchestration, evaluation suites | Versioned agent configs/prompts in Git; eval sets (normal, edge, injection) with CI pass thresholds; tool allowlist | `control-panel` + CI | Adopted |
| 8 | Operational automation — scheduled, event-driven, API-driven | Workflow triggers, alert rules on IBOR writes, scheduled screening/recon/report jobs, webhook entry points | `workflow` + `control-panel` | Adopted |
| 9 | Development environments — IDE, notebooks, compute | Standard repo dev environment; no in-platform IDE | repo tooling | Out of scope |
| 10 | Human+AI app building — low/no-code, object analytics, geospatial | Low-code metric/model authoring; no general app builder | `analytics` | Partial |
| 11 | Package, release, deploy — packaging, dependency mgmt, release channels | Standard CI/CD: Gradle build, pinned images, staging → production | CI/CD | Adopted (standard) |
| 12 | Enterprise automation — AI analysts, code assist, OSDK, AI-enabled apps | NL query over fund data; AI drafting; screening/DDQ/report generation — all inside product features | `control-panel`, `deal-sourcing` | Adopted (productized) |

## Consumers

The reference serves agents, operational, developer, analyst, and governance teams plus automations. Mesta-Asset's consumer map:

| Consumer | Serves through |
| --- | --- |
| Analysts | Deal Pipeline, DDQ workspace, IC report editor, NL query |
| Investment/ops teams | Dashboards, Control Panel inbox, workflow tasks |
| Agents | Tool-allowlisted API access to Ontology + ledger; no direct store access |
| Automations | Alert rules, scheduled screening, recon, report generation |
| Governance teams | Approval queues, audit views, eval dashboards, model registry |
| Developers | This repo — modules, ontology, eval sets, adapter configs |

## Hard rules carried into implementation

1. **Agents never hold raw credentials or bypass `api`.** Agent tool surface = allowlisted API operations; every call is authenticated, permission-checked, and logged.
2. **Documents are untrusted input.** Extraction models read pitch decks; instructions inside them can never reach the tool layer (prompt-injection boundary).
3. **Model catalog is explicit.** Approved models, versions, providers, and residency constraints are registered; features declare which catalog entry they use.
4. **Evaluation gates releases.** Screening, DDQ answers, and report generation ship behind eval thresholds (accuracy, citation correctness, injection resistance) enforced in CI.
5. **Semantic cache is scoped.** Cached model responses respect entity permissions — no cross-user leakage through cache hits.
6. **Cost limits are per-feature.** Screening, DDQ, drafting, and NL query have independent budgets and circuit breakers.

## Deliberately excluded

- Self-serve agent building by end users — agent behaviors are product features, versioned and evaluated by the team
- In-platform IDE/notebook environments
- General-purpose app builder (see `application-surface.md`)
- Public/uncontrolled model endpoints for Confidential data
