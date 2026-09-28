# ADR-0005: Python Agent Sidecar for AIP Workflows

- Status: Superseded by [ADR-0006](0006-kotlin-native-aip-orchestration.md)
- Date: 2026-09-28
- Risk tier: T2 (new deployable, confidential-data processor, AI-supported decisions)
- Decision owner: CTO + Blue Team (AI governance per `AGENTS.md` and `docs/data-security-governance.md`)
- Depends on: [ADR-0001](0001-platform-architecture.md), [ADR-0002](0002-self-hosted-supabase.md), [ADR-0004](0004-neo4j-graph-store.md)
- Issue: #6 (slice 11+, AIP workflows)

## Context

The deterministic core is shipped: analytics (§2–§7, §9), IBOR (slice 4), look-through, reconciliation, post-trade compliance, the report service with an approval gate, the workflow state machine with segregation of duties, the hash-chained audit log, and the deal-sourcing prospect pipeline with screening, an IC gate, and a DD checklist (#201). The Palantir AIP workflows — Investment Analyst / Screening & DD, Fundraising (RFP/DDQ), Operating Partners, Asset/Portfolio Managers, CRM — sit on top of that core.

Two AI decision points already run in `modules/control-panel`: document classification (point 5) and claim support (point 6), through a Kotlin OpenRouter judgment client with synthetic eval sets and a CI-enforced threshold (#52). Those are single-shot classifiers. The AIP workflows are multi-step agents — planning, tool use, retrieval, drafting — which outgrow a single-shot judgment call.

Owner decision (2026-09-28): run the agent layer as a **Python sidecar** (LangGraph / deepagents), not Kotlin-native in `control-panel`. This buys the richer agent-orchestration ecosystem at the cost of a second runtime and image, and it introduces a new confidential-data processor that Blue Team must review before any agent code ships.

## Decision

**Adopt a Python agent sidecar as a separate Dokploy service** driving the AIP workflows, with the Kotlin platform as its only client and its only tool surface.

### Boundaries

- **Deployment.** Its own image and Dokploy service on the internal `dokploy-network`; no Traefik label, so no public route (bolt/agent traffic stays private, like `octo-neo4j-db`).
- **Data path.** LP identities and Strictly-Confidential data (`docs/data-security-governance.md`) reach a model only through zero-data-retention endpoints. The client always sends the ZDR flag; a request routed to a non-ZDR-listed model is **rejected fail-closed**, never sent.
- **Tool boundary.** Agents have **no direct database access**. They call back into the Kotlin `api` over the same JWT + RBAC surface a human uses, under a dedicated agent service principal. Agents open workflow tasks and draft artifacts; they never write the ledger and never approve their own output (segregation of duties, `workflow` state machine).
- **Model roles.** An approved-model registry (versioned in the sidecar repo) records `model id`, ZDR status, and role: a judge model scores, a separate drafter model writes prose — jev judges, it never drafts.
- **Evals.** Every workflow ships normal / edge / injection eval cases before it goes live (`AGENTS.md`), CI-gated on a threshold, mirroring #52.

### Ownership

The sidecar is its own deployable with its own CODEOWNERS and a Blue Team-reviewed tool manifest (`agent-supply-chain` / `mcp-security-audit`). The Kotlin core owns financial truth and the human-in-the-loop gates; the sidecar owns orchestration and drafting only.

## Consequences

### Positive

- LangGraph state machines, retrieval, and the Python AI ecosystem, without bending the JVM core around agent tooling.
- The AI blast radius is isolated from the financial core: the sidecar can be stopped, rate-limited, or rolled back independently, and the deterministic platform keeps working without it.

### Negative

- A second language and runtime, a second image, and cross-service latency + auth to operate and monitor.
- A new supply-chain surface (Python dependencies) that needs scanning and a signed tool manifest.
- Two model providers/keys and an approved-model registry to keep current.

### Neutralized risks

- PostgreSQL stays the ledger of record; agents are tool-mediated and human-gated, so a misbehaving agent produces a task or a draft, never a financial write.
- ZDR fail-closed keeps confidential LP data off non-compliant endpoints.
- Deterministic core is unaffected if the sidecar is down — agent features are additive.

## Acceptance criteria

These gate implementation — the review the decision names. No agent code ships until they hold.

- [ ] Blue Team review of OpenRouter (and any second provider) as a data processor, plus the sidecar tool manifest.
- [ ] Approved-model registry defined with the fields `docs/data-security-governance.md` requires; ZDR fail-closed enforced and tested.
- [ ] API auth model for the agent service principal agreed (RBAC/ABAC scope; least privilege — tasks and drafts only).
- [ ] Per-workflow eval sets (normal / edge / injection) with a CI-enforced threshold, mirroring #52.
- [ ] Sidecar image + Dokploy service on `dokploy-network`, no public route; supply-chain scan green.
- [ ] First workflow (Investment Screening & DD) behind a feature flag, tasks-only, no autonomous writes.
