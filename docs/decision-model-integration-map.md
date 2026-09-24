# Decision model — integration map

Where the `typesafe/jev-1.13` decision model plugs into Mesta-Asset, mapped against the actual
ontology types in `ontology/mesta-investment.tql`. Client implementation lives in
`modules/control-panel/src/main/kotlin/com/mesta/asset/controlpanel/judgment/`.

- **Model:** `typesafe/jev-1.13` via the OpenRouter decisions endpoint
- **Risk tier:** T2 — models used for decisions. Preconditions listed at the end
- **Status:** points 5 and 6 are implemented and persist to the append-only staging tables `mesta.document_classification` and `mesta.claim_assessment`. Point 1 is unblocked — `screening-decision` now carries `model-version`, `result-distribution`, `confidence-level`, and `external-id` — but not yet implemented. Points 8, 9, and 10 are not started

## How to read this

Three properties of the model determine every mapping below:

1. **It returns typed answers, not prose.** `noul` gives a probability, `choice` gives an option plus a full distribution, `score` gives a value plus a distribution. There is no generated text, so it can never fill a free-text field such as `rationale`.
2. **Two stores, two kinds of decision record.** TypeDB owns entities, relationships, and provenance. PostgreSQL owns the ledger, workflow, and audit. A decision about an *entity* belongs in TypeDB; a decision about a *task* belongs in PostgreSQL.
3. **The probability distribution is the point.** The selected option alone discards the information that makes the answer useful, so every integration point below has to decide where the distribution is stored.

## Integration points

| # | Module | Decision | Question | Ontology / store target | Human gate |
| --- | --- | --- | --- | --- | --- |
| 1 | `deal-sourcing` | Screen an inbound deal against configured criteria | `choice` (5 options) | `screening-decision` + `screening-of` → `deal` | Decline and IC submission always human |
| 2 | `deal-sourcing` | Does this material answer this criterion? | `noul` | Drives the evidence request, not a stored field | Evidence request raised, not auto-failed |
| 3 | `deal-sourcing` | Does the submitted material support this DDQ answer? | `noul`, `score` | `extracted-claim` + `extraction-source` → `document` | Analyst reviews every suggestion |
| 4 | `deal-sourcing` | Is this IC report claim supported by its citation? | `noul` | `extracted-claim.confidence-level` | Editor confirms before export |
| 5 | `ingestion` | Classify an inbound document | `choice` (9 options) | `document.document-type` | Low risk; misrouting is correctable |
| 6 | `ingestion` | Is this extracted claim supported by the quoted passage? | `noul` | `extracted-claim.confidence-level`, `extraction-source.confidence-level` | Feeds review queue, never auto-approves |
| 7 | `ingestion` | Are these two records the same entity? | `noul` | `party.external-id`, `source-attribution` | Merge is human-approved |
| 8 | `recon` | Classify a source-versus-IBOR break | `choice` | PostgreSQL recon records | Break disposition is human |
| 9 | `control-panel` | Is this item material to this portfolio company? | `noul`, `score` | PostgreSQL alert records | Alert is a prompt to look, not an action |
| 10 | `workflow` | Route or prioritise a task | `choice`, `score` | PostgreSQL workflow state | Routing only, no approval power |

Points 8, 9, and 10 target PostgreSQL because workflow, alerting, and audit state live there per ADR-0001 — not because the ontology is missing something.

## Exact type matches

Three ontology definitions already match the model's answer shapes without modification.

| Ontology | Values | Model equivalent |
| --- | --- | --- |
| `screening-result` | `pass`, `conditional`, `fail`, `unknown`, `conflicting` | A five-way `choice`; criteria map 1:1 to the options |
| `document-type` | `pitch-deck`, `ddq`, `financials`, `icap-report`, `memo`, `legal`, `lp-report`, `tear-sheet`, `other` | A nine-way `choice`; the enum is the criteria map |
| `confidence-level` | `double @range(0..1)` | The `confidence` field on choice and score answers, and the `noul` probability |

Point 5 is the cleanest integration in the platform: an enum in the schema that is exactly the option set of a choice question, on a non-financial field, with a correctable failure mode.

## Gaps that need an ontology change

These are real blockers for the affected points, and ontology changes are T2 and CTO-owned.

| Gap | Affects | Consequence |
| --- | --- | --- |
| ~~No model-version attribute on `screening-decision` or `extracted-claim`~~ — closed | 1, 3, 4, 6, 7 | `screening-decision` and `extracted-claim` now own `model-version` and `external-id` (the provider request id). `criterion-version` still records which *criteria* were used |
| ~~`screening-decision` has no `confidence-level` and no place for a distribution~~ — closed | 1 | `screening-decision` now owns `confidence-level` and `result-distribution`, so the rule that `unknown` never silently becomes `fail` has the distribution to read |
| No reconciliation-break type | 8 | Break records live only in PostgreSQL, so graph-versus-ledger reconciliation has no typed target |
| No alert-rule or news-item type | 9 | Alert state is untyped relative to the ontology |
| No identity-merge provenance type | 7 | `supersedes` exists for ledger corrections, not entity merges. A merge needs its own auditable record |
| `rationale` cannot be model-written | 1 | The model produces no prose. Cited reasons for a recommendation must be assembled from the criteria and the underlying `extracted-claim` citations, not generated |

The first two were the screening blockers and are closed. `external-id` doubles as the provider request id, linking a TypeDB decision back to the staged row in `mesta.claim_assessment`.

## Explicit exclusions

| Excluded | Why |
| --- | --- |
| IBOR derivation, positions, commitments | Deterministic and reproducible from the append-only ledger |
| IRR, TVPI, MOIC, DPI, valuation models | Probabilistic input to a reported figure is not auditable |
| IC report drafting, LP emails, DDQ question generation | These need generated prose. A decision model returns typed values; generation belongs to the approved generative model |
| Metric and model definition authoring | Definitions touching financial data are T2 and belong to human review |
| Authorization and permission decisions | Deterministic RBAC/ABAC only |
| Ontology and SHACL validation | Structural validation is deterministic and gates ingestion |

The distinction worth holding onto: this model **judges**, it does not **write**. Point 4 is the clearest example — it verifies a claim someone else drafted rather than drafting one.

## Placement

```text
document ──► extraction ──► extracted-claim (+ citation)
                                  │
                                  ▼
                        decision model client
                        (control-panel, allowlisted)
                                  │
                    ┌─────────────┴─────────────┐
                    ▼                           ▼
          screening-decision              claim confidence
          (+ screening-of → deal)         (+ extraction-source)
                    │                           │
                    └─────────────┬─────────────┘
                                  ▼
                        human review queue
                                  │
                                  ▼
                        workflow approval ──► outbound
```

Two rules carried from ADR-0001 and the AI architecture:

- **Adapter isolation.** No module outside the client adapter depends on the OpenRouter or TypeSafe API shape. Swapping the provider must be an adapter change.
- **The guard is not optional.** `decide()` requires a declared `DataClassification` and refuses Confidential and Strictly Confidential state before the transport is reached. Point 1 screens real deal materials, so this is load-bearing rather than ceremonial.

## Suggested sequence

Ordered by risk, not by value.

| Order | Point | State | Why this order |
| --- | --- | --- | --- |
| 1 | 5 — document classification | Implemented | Exact enum match, non-financial field, correctable failure, no ontology change needed |
| 2 | 6 — claim support | Implemented | Improves every downstream decision and enforces citation integrity. Uses existing `confidence-level` |
| 3 | 1 — deal screening | Unblocked, not implemented | Highest value. The model-version and confidence gaps are closed; the remaining preconditions are the eval set and approved-model registry entry |
| 4 | 8 — recon triage | Not started | T2, and needs a break type in the ontology |

Where the first two live:

| Point | Code |
| --- | --- |
| 5 | `modules/ingestion/.../classification/` — `DocumentType`, `DocumentClassificationCriteria`, `DocumentClassifier` |
| 6 | `modules/ingestion/.../extraction/` — `ClaimSupportPolicy`, `ClaimSupportAssessor` |

Both return a decision record with the model lineage and a `requiresReview` flag. Persistence lives in `modules/ingestion/.../persistence/` — `DocumentClassificationStore` and `ClaimAssessmentStore` interfaces with `JdbcDecisionStore` writing to the append-only tables `mesta.document_classification` and `mesta.claim_assessment` (V2 migration). Corrections are new rows linked by `supersedes_id` with a mandatory rationale, replay is rejected by a `(source_system, external_id)` unique index, and the full probability distribution is stored as `jsonb` — the argmax alone cannot show how marginal a decision was. The TypeDB attributes (`document.document-type`, `extracted-claim.confidence-level`) are written downstream after review; staging is the record of what the model said.


## Preconditions

- [ ] Approved-model registry entry: provider, version, residency, licence, per-feature cost limit
- [ ] Eval set covering normal, edge, and prompt-injection cases, thresholds in one reviewable file
- [ ] Security Blue Team review of OpenRouter as a processor — state transits OpenRouter and TypeSafe
- [ ] Confirm whether Confidential state may be sent at all, or the feature runs synthetic-only
- [ ] T2 plan agreed in a GitHub issue before any integration point is wired
- [x] Ontology change (T2, CTO) for model version and confidence storage before point 1 — done: `model-version`, `result-distribution`, `confidence-level`, `external-id` on `screening-decision`; `model-version`, `external-id` on `extracted-claim`

## Notes

- The model page at `https://openrouter.ai/typesafe/jev-1.13` returns 404 to automated fetches. Model facts were read from `https://openrouter.ai/api/v1/models/typesafe/jev-1.13/endpoints` instead.
- The endpoint is `/api/alpha/decisions`, alpha status. An earlier default with a doubled `api` segment returned 404 (#70).
