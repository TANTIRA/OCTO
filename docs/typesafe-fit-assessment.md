# TypeSafe — fit assessment

Evaluation of [TypeSafe](https://docs.typesafe.ai/llms-full.txt) as a judgment layer for Mesta-Asset. No API calls were made and no agent skill was installed; this is a documentation-only assessment.

- **Risk tier of any implementation:** T2 — judgment used for investment decisions and financial-data classification
- **Status:** proposed, not approved. Blocked on Security Blue Team review and an approved-model registry entry

## What TypeSafe is

A hosted evaluation API. You send a `state` (string or structured JSON) plus a map of typed questions, and get typed answers back.

| Primitive | Question shape | Answer shape |
| --- | --- | --- |
| `noul` | Yes/no | Probability the answer is yes (0..1) |
| `choice` | Pick one of N options | Chosen option plus the full probability distribution |
| `score` | Numeric judgment against criteria | Value plus distribution |

It is not a general-purpose LLM. It returns calibrated probabilities for a fixed judgment, with no free-form generation. Questions in one request are evaluated in parallel and are cheap, so asking questions you may not need is close to free. Confidence is derived from the distribution, and "I don't know" is a usable signal.

## Governance constraints

These are blocking as written, not advisory.

| Constraint | Source | Effect |
| --- | --- | --- |
| Confidential and Strictly Confidential data never goes to a public LLM API | `AGENTS.md` | Real fund, LP, portfolio-company, or deal content cannot be sent as `state` |
| Third-party skills and plugins need Security Blue Team review first | `AGENTS.md` | The TypeSafe agent skill cannot be installed before review |
| Model and dataset source and licence are recorded | `AGENTS.md` | TypeSafe needs an approved-model registry entry: provider, version, residency, licence, cost |
| Prompts or models used for decisions are T2 | `AGENTS.md` risk tiers | Screening and DDQ usage requires 2 reviewers including a Tech Lead, security review, and a tested rollback |
| Model catalog is explicit | `ai-architecture.md` rule 3 | Any feature must declare which catalog entry it uses |
| Evaluation gates releases | `ai-architecture.md` rule 4 | An eval set with CI pass thresholds is required before enabling |
| Agents reach data only through the allowlisted API | `ai-architecture.md` rule 1 | The client lives in service code, never in client-supplied calls |

The practical consequence: TypeSafe is only reachable with synthetic or masked state unless it offers private deployment or a no-retention arrangement. That is an open question, not a settled one.

## Where it fits

The strongest signal is that the ontology already models exactly what TypeSafe returns. `screening-result` is `@values("pass", "conditional", "fail", "unknown", "conflicting")` — a five-way `choice` — and `confidence-level` is `double @range(0..1)`, which is the probability TypeSafe hands back.

| Capability | Module | Primitive | Why |
| --- | --- | --- | --- |
| Screening criteria classification | `deal-sourcing` | `choice` | The five-value `screening-result` maps 1:1 to a choice question. Store the selected option as the result and the distribution as `confidence-level` |
| Evidence-gap and inconsistency detection | `deal-sourcing` | `noul` | "Does this material answer this criterion?" is a yes/no with a usable probability |
| DDQ answer plausibility review | `deal-sourcing` | `noul`, `score` | Flags suggested answers that contradict the submitted materials before an analyst sees them |
| Extracted-claim validation | `ingestion` | `score` | Feeds `extracted-claim.confidence-level` for claims pulled from pitch decks and PDFs |
| Cross-source entity resolution | `ingestion` | `noul` | Duplicate company, person, or LP records across a CRM and a financial-data provider — the same shape as the duplicate-resume example in the TypeSafe docs |
| Reconciliation discrepancy triage | `recon` | `choice` | Classify a break as timing, amount, FX, or missing event before it becomes a workflow task |
| Alert and news relevance | `control-panel` | `noul`, `score` | Decides whether a matched item is worth a human's attention |
| Workflow task prioritisation | `workflow` | `score` | Orders the Control Panel inbox |

The distribution matters more than the argmax. The existing rule that `Unknown` never silently becomes `Fail` is implementable by reading the distribution rather than the top option: a low-confidence result routes to evidence request or manual review instead of an automated transition.

## Where it does not fit

| Excluded | Reason |
| --- | --- |
| IBOR derivation, positions, commitments, drawdowns | Must stay deterministic and reproducible from the append-only ledger |
| IRR, TVPI, MOIC, DPI, valuation models | Probabilistic inputs to reported figures are not auditable |
| LP-facing reports and disclosures | No probabilistic component may reach a disclosed number |
| Free-form drafting (IC reports, LP emails) | That is the approved generative model's job, not a classifier's |
| Authorization and permission decisions | Deterministic RBAC/ABAC only |
| Ontology and SHACL validation | Structural validation stays deterministic and is a CI gate |

TypeSafe proposes a judgment. It never writes to the ledger, never approves a stage transition, and never sends anything outbound.

## Architectural placement

```
documents / source records
        │
        ▼
ingestion ──► staging (masked or synthetic state)
        │
        ▼
TypeSafe client adapter  ◄── allowlisted tool, service-side only
        │
        ▼
screening-decision / extracted-claim
   owns screening-result, confidence-level
        │
        ▼
human review ──► workflow approval ──► outbound
```

Two placement rules:

1. **Isolate the provider behind an adapter.** ADR-0001's vendor-neutrality consequence — no module outside the adapter depends on a vendor's API or format — applies to a judgment provider exactly as it does to a data provider. Swapping TypeSafe for another evaluator or a self-hosted model must be an adapter change.
2. **Persist the judgment, not just the verdict.** Every call stores the selected option, the distribution, the model version, and the source citation on the resulting ontology record, so a later reviewer can see why the platform said what it said.

## Before adopting

- [ ] Security Blue Team review of the API and the agent skill
- [ ] Confirm whether private or self-hosted deployment exists, or accept a synthetic-only data path
- [ ] Approved-model registry entry: provider, version, residency, licence, per-feature cost limit
- [ ] Eval set covering normal, edge, and prompt-injection cases, with thresholds in a single reviewable file
- [ ] Decide the adapter boundary and which module owns the client
- [ ] T2 plan agreed in the GitHub issue before any code is written

## Open questions

- Does TypeSafe offer private deployment, or a no-retention contract sufficient for Confidential data?
- Does sending masked state still constitute processing under UU PDP No. 27/2022?
- What are the real latency and cost figures at screening volume, per inbound opportunity?
- How does the provider's calibration drift over time, and how is that detected?
