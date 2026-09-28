# ADR-0006: Kotlin-native AIP Orchestration + On-platform Model for Confidential Workflows

- Status: Proposed
- Date: 2026-09-28
- Risk tier: T2 (AI-supported decisions, confidential-data processing)
- Decision owner: CTO + Blue Team (AI governance per `AGENTS.md` and `docs/data-security-governance.md`)
- Supersedes: [ADR-0005](0005-python-agent-sidecar.md)
- Depends on: [ADR-0001](0001-platform-architecture.md), [ADR-0002](0002-self-hosted-supabase.md), [ADR-0004](0004-neo4j-graph-store.md)
- Issue: #6 (slice 11+, AIP workflows)

## Context

ADR-0005 chose a Python LangGraph/deepagents sidecar for the AIP agent layer. That decision gated *all* agent code on Blue Team review of a new image and a new data processor. Two facts since then reshape it:

1. **The Kotlin path is already governed.** #255 shipped the approved-model registry and zero-data-retention (ZDR) fail-closed enforcement in the existing `modules/control-panel` judgment client — the same in-production path decision points 5 and 6 use (#52). A model that is not registered, not ZDR, or wrong-role is rejected before any request leaves the process. This is the control a new sidecar would have had to re-earn from Blue Team.

2. **AIP investment inputs are Confidential, and the platform forbids sending them to a public endpoint.** `docs/data-security-governance.md` (Data classification) classes pitch decks, DDQs, company financials, fund data, valuations, and investment memos as **Confidential**, and LP identities/commitments and deal-room material as **Strictly Confidential** ("no public AI processing"). The doc's AI rule is explicit: *do not send Confidential or Strictly Confidential data to an unapproved model or public AI endpoint*. The `mayLeavePlatform` guard already enforces this — `decide()` rejects Confidential+ state outright.

The consequence: the runtime choice (sidecar vs. Kotlin-native) is **not** the binding constraint. The binding constraint is *where the model runs*. The external OpenRouter/jev endpoint — ZDR or not — is a public processor and may serve only Public/Internal features. Every AIP investment workflow processes Confidential data and therefore needs an **on-platform / self-hosted approved model**, regardless of runtime.

## Decision

1. **Runtime: Kotlin-native orchestration in `modules/control-panel`.** Reuse the ZDR-governed judgment client and the existing service/deployable. No second language, image, or processor to have Blue Team re-review. This supersedes the sidecar.
2. **Two data paths, by classification.**
   - *Public/Internal* AI features (the shipped decision points 5/6, and any future Internal-only helper) use the external ZDR endpoint through the #255 registry.
   - *Confidential/Strictly-Confidential* AIP investment workflows use an **on-platform approved model** so the data never leaves the platform boundary. Standing that model up (self-hosted inference on the OCTO/Supabase network, or a data-owner-approved private processor) is the real prerequisite for slice 11+.
3. **Governance unchanged.** Agents recommend; humans approve declines, IC submissions, external reports, and material changes (`data-security-governance.md`). Agents write tasks and drafts, never the ledger or their own approval (segregation of duties). Every workflow ships a per-workflow eval set — accuracy, prompt-injection defence, and access-control-leakage — CI-gated on a threshold like #52, and every model is entered in the registry with its role, ZDR/on-platform status, and approval.

## Consequences

### Positive

- No new runtime, image, or processor to operate or to put through Blue Team — the AI blast radius stays on the already-reviewed Kotlin path with the #255 controls.
- The binding blocker is named precisely: an on-platform model, not an orchestration framework. Internal-only AI features can proceed now.

### Negative

- Multi-step orchestration is hand-rolled in Kotlin rather than a graph framework; acceptable for the near-term workflows, revisit if orchestration complexity grows.
- AIP investment workflows stay blocked until an on-platform model exists — this ADR does not remove that gate, it relocates it from "review a Python image" to "provision an on-platform model."

### Neutralized risks

- Confidential data cannot reach a public endpoint: the `mayLeavePlatform` guard plus the #255 registry are both fail-closed.
- The deterministic core (screening, prospect pipeline, compliance, reports) is unaffected and already delivers the investment workflow without AI; agents are additive assistance.

## Acceptance criteria

- [ ] An on-platform / self-hosted model (or a data-owner-approved private, non-public processor) is provisioned for Confidential inputs, on the internal network, and registered in the model registry (role, on-platform status).
- [ ] Data owner records the classification and permitted purpose for each AIP workflow's inputs.
- [ ] Per-workflow eval sets (accuracy, injection, access-control leakage) with CI-enforced thresholds, mirroring #52.
- [ ] Agent auth model (service principal, least-privilege RBAC/ABAC — tasks and drafts only) agreed.
- [ ] First workflow (Investment Screening & DD) advisory-only behind a feature flag, no autonomous writes.
