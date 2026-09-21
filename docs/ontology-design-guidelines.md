# Ontology Design Guidelines

Design principles and review checklist for `ontology/mesta-investment.tql` and all future Ontology changes. Adapted from established ontology-platform best practices, translated to TypeDB/TypeQL 3.0 and the private-equity domain. Ontology changes are T2 and CTO-owned per `AGENTS.md`.

## Principles in priority order

| Priority | Principle | Core idea |
| --- | --- | --- |
| 1 | Domain-driven design | Model the investment business, not the source feeds |
| 2 | Do not repeat yourself | One canonical type per concept; rule of three triggers refactor |
| 3 | Open for extension, closed for modification | Stable core types; extend via linked types and new subtypes |
| 4 | Composition over deep hierarchies | Capability via role-playing and shared attributes, not deep chains |

### 1. Domain-driven design

Entity types represent real investment concepts — `fund`, `deal`, `limited-partner`, `valuation-event` — never source tables or API payloads. Source quirks (`FUND_NM`, `DT_LAST_MOD`) stay inside `ingestion` adapter mappings.

**Mesta-Asset anti-patterns:**

- A `PreqinFundRow` or `CrmExportLine` entity type — source shape leaking into the domain
- 1:1 column mapping producing attributes like `fund.record12` or `deal.misc_field`
- One entity type holding a spreadsheet row that contains a company, a round, and a contact — split into `operating-company`, `deal`, `person`
- Naming from source conventions instead of business language (`deal.effective-date`, not `deal.effDt`)

**Applied in our schema:** `ledger-event` and `valuation-event` are separate from `fund`/`investment` because a row representing an observation is a different entity than the thing observed. Identity and observation are never the same type.

**Hidden types:** types that exist for technical purposes (staging artifacts, join helpers) rather than domain semantics must be flagged `hidden` in ontology metadata so default views and AI grounding stay clean. `extracted-claim` and `screening-decision` are domain-visible (auditable business facts); a future `sync-checkpoint` type would be hidden.

### 2. Do not repeat yourself

One canonical type per concept. Shared shape → shared attribute types or a supertype; shared logic → TypeQL function or metric-DSL definition.

**Mesta-Asset anti-patterns:**

- `FundCashFlow`, `LpCashFlow`, `DealCashFlow` — three types for one concept; we use one `ledger-event` + `cash-flow-attribution` relation
- `SalesContact`, `InvestorContact`, `MgmtContact` — one `person` + `contact-for`/`employment`/`board-seat` relations carry the distinction
- Copy-pasted screening rules per strategy with minor variations — shared criteria library with strategy-scoped parameters
- A second `company` type created because `operating-company` "is for portfolio companies only" — extend, don't fork

Rule of three: one duplicate is tolerable, two is a warning, three means refactor — consolidated into the canonical type or a shared abstraction.

### 3. Open for extension, closed for modification

Core types (`party`, `fund`, `deal`, `investment`, `ledger-event`) are load-bearing — dashboards, recon, screening, and AI grounding depend on them. Once in production their essential shape is locked.

**Extension patterns (preferred):**

```typeql
# New capability = new subtype or new linked type, never property creep on core
entity hedge-fund, sub fund, owns redemption-terms;
entity portfolio-review, owns review-date, owns outcome;
relation review-of, relates review-side @card(1), relates subject-side @card(1);
```

**Mesta-Asset anti-patterns:**

- Adding `hedge-fund-specific-field` attributes to `fund` — null for every PE fund
- Editing `commitment` to add a strategy-specific clause — extend with a linked type instead
- Changing a core attribute's value type — that's a MAJOR Ontology change (SemVer major, deprecation first)

Security boundary: extensions inherit the classification of the core type they touch. An extension must never widen access to existing attributes.

### 4. Composition over deep hierarchies

TypeQL supports single inheritance; composition comes from **role-playing** and **shared attributes** — the practical equivalent of capability interfaces.

**How Mesta-Asset composes:**

- Capabilities are roles: anything that can own something plays `ownership:owner`; anything ownable plays `ownership:asset`. A `fund` can be both (fund-of-funds) without a new type.
- Shared attributes are mixins: `external-id`, `effective-date`, `currency-code`, `recorded-at` attach to any type.
- Workflows target roles, not concrete types: look-through walks `ownership` edges regardless of what entity plays each side.

**Anti-patterns:**

- Intermediate types that exist only to combine capabilities (`InvestableHoldingVehicle`)
- Deep chains — our deepest is `party → organization → fund-manager` (3 levels); anything deeper needs justification in the PR
- Relations targeted at a concrete type when the role could accept any `party`

## Review checklist (Ontology PRs)

- [ ] New types model domain reality — no source-system artifacts
- [ ] Names are business language; no source abbreviations
- [ ] No duplicate of an existing type/attribute — searched first
- [ ] Extension via subtype or linked type, not core-type property creep
- [ ] Roles designed for polymorphism where the domain allows it
- [ ] Cardinalities explicit where `@card(0..1)`/`@card(0..)` defaults are wrong
- [ ] Enums use `@values`; codes use `@regex`; ranges use `@range`
- [ ] Technical-only types flagged hidden
- [ ] Consumer impact listed (API, dashboards, screening, recon, AI grounding)
- [ ] SemVer level stated; deprecations marked before deletion
- [ ] TypeQL 3.0 syntax verified — CI schema validation green

## Structural guidance

### Normalization and derived values

**Store each fact once; derive the rest.**

| Value type | Characteristics | Mesta-Asset mechanism | Example |
| --- | --- | --- | --- |
| Pre-computed | From attributes on the same entity; inputs change only via ingestion | Pipeline transform in `ingestion` | `display-name` normalized from legal name |
| Dynamically derived | Depends on linked entities or ledger events that change via actions | TypeQL function or metric-DSL definition evaluated at query time | `fund.committed-amount` reconciles against `sum(commitment.committed-amount)`; TVPI derives from `ledger-event` + `valuation-event` |
| Projection | Derived value materialized for read performance | Documented projection table in PostgreSQL, rebuilt by `analytics` | Dashboard KPI tiles |

**Rules:**

- Never store a manually-maintained count or aggregate as an attribute (no `fund.deal-count` written by a job that can drift) — compute from relations.
- `fund.committed-amount` is stored because it is a fact reported by the manager; the sum of LP `commitment` amounts is a **reconciliation check**, not the same fact. Both exist; `recon` compares them.
- Projection tables are allowed at scale (>10k entities per aggregation) but must name their derivation and rebuild strategy. A projection is a cache, never a source.

### Structs — not supported; use relations

TypeQL 3.0 has no struct value types. Grouped facts with metadata are modeled as **entities or relations carrying their own attributes**:

```typeql
# Reference pattern: address struct with subfields and metadata
# TypeQL equivalent: attribute group on the owning entity, or a
# relation when the grouped value has its own provenance

attribute address-line, value string;
attribute city, value string;
attribute postal-code, value string;
# ... owned together on the entity for simple cases
```

For values carrying provenance — especially AI-extracted claims — use the `extracted-claim` entity + `extraction-source` relation pattern already in the schema: claim, confidence, document, and location travel together, replacing "struct with metadata."

### Interfaces — subtyping + roles

TypeQL has no interface construct. The equivalents:

| Reference construct | TypeQL equivalent |
| --- | --- |
| Interface with shared properties | Shared attribute types + abstract supertype |
| Capability interface (`Valuable`, `Screenable`) | Role-playing: `plays valuation-of:subject-side`, `plays screening-of:deal-side` |
| Interface-targeted workflows | Queries/functions matching the role, not the concrete type |
| Taxonomic interface | Subtype hierarchy (`party → organization → fund-manager`) |

### Links — relations carry metadata natively

TypeDB relations own attributes, so the "object-backed link" pattern is the default. Direct links are for relationships with no metadata of their own.

| Relationship | TypeQL design | Metadata carried |
| --- | --- | --- |
| Fund managed by manager | `fund-management` relation | `effective-date` |
| LP committed to fund | `commitment` relation | `committed-amount`, `currency-code`, `effective-date` |
| Person employed at org | `employment` relation | `role-title`, `effective-date`, `end-date` |
| Ownership edge | `ownership` relation | `ownership-pct`, `effective-date`, `end-date` |

Never collapse a dated/quantified relationship into attributes on the entity — `person.current-employer` breaks on history; `employment` relation survives it.

### Naming conventions

TypeQL labels are kebab-case. Our conventions:

| Element | Convention | Examples |
| --- | --- | --- |
| Entity/relation/attribute labels | kebab-case, singular, domain nouns | `operating-company`, `ledger-event`, `committed-amount` |
| Calendar dates | `*-date` | `effective-date`, `end-date`, `as-of-date`, `founded-date` |
| Timestamps | `*-at` | `occurred-at`, `recorded-at` |
| Roles | `*-side` for symmetric/participant roles; semantic names for asymmetric | `owner`/`asset`, `employee`/`employer`, `event-side`/`position-side` |
| Enums | `@values` on a named attribute type | `deal-status`, `flow-type` |
| Ambiguous values | Qualified names | `monetary-amount`, `ownership-pct`, `confidence-level` — never `value`, `amount`, `score` |

### Security design — no security-driven type splits

Never fork an entity type for access control (`PublicDeal`/`RestrictedDeal`). One `deal` type; restrictions are policy:

- **Row-level** → ABAC entity scoping (user's fund/deal/company assignments)
- **Column-level** → field restrictions (e.g. `limited-partner.committed-amount` visible to IR, not analysts)
- **Cell-level** → intersection of both (a restricted LP's commitment amount)

Domain boundaries drive the policy: deal teams see their deals; IR sees LP-facing data; portfolio company financials follow fund assignment. New relations must be reviewed for access-path leakage — a link can expose a restricted entity indirectly.

## Anti-pattern catalog

Translated to the Mesta-Asset stack: pipelines = `ingestion` jobs, actions = governed commands in `api`/`workflow`, automations = workflow triggers and alert rules, functions = TypeQL functions and the metric DSL, schedules = batch jobs.

| Anti-pattern | Mesta-Asset form | Resolution |
| --- | --- | --- |
| **System Silos** | `PreqinFund`, `CrmCompany`, `AdminExportLp` — one type per vendor | One canonical type; merge in adapters with declared precedence rules (`recon` arbitrates conflicts) |
| **Kitchen Sink** | `_batch-id`, `source-row-num`, `etl-timestamp` as domain attributes | Technical metadata stays in staging/provenance records; domain attributes must answer "would a user search or decide on this?" |
| **Department Silos** | `IrLp`, `DealTeamProspect`, `OpsCompany` — per-team copies | One `limited-partner`/`deal`/`operating-company`; team specifics live in scoped attributes, relations, or views |
| **God Object** | `instrument` holding funds, deals, securities, and companies via a `kind` attribute | Distinct types; shared shape via supertypes and role-playing |
| **Golden Hammer** | A command users click to "refresh metrics"; a batch job assigning tasks; a TypeQL function concatenating names | Match tool to job — see table below |
| **Action Sprawl** | `set-deal-status`, `set-deal-owner`, `set-deal-sector` as separate commands | Business-operation commands: `advance-deal-to-diligence` bundles status, owner, tasks, notification |
| **Time Machine** | `Fund2024`, `Fund2025`, or `valuation-v3` objects duplicating each other | One entity; history via `supersedes` + linked `valuation-event`/`ledger-event` instances |
| **Misnomer** | `amount`, `value`, `type`, `related`, `misc` | `committed-amount`, `monetary-amount`, `ownership-pct`, relation names that read as the relationship |

### Golden Hammer — tool selection

| Need | Correct tool | Wrong tool |
| --- | --- | --- |
| Batch normalize vendor feeds | `ingestion` pipeline | Command or function |
| Compute IRR/TVPI from ledger | Metric definition in `analytics` | Stored attribute, manual command |
| Alert on metric threshold breach | Alert rule (`control-panel`) | Polling batch job, user check |
| React to new prospect | Workflow trigger → screening eval | Scheduled scan, manual triage |
| Analyst approves a draft | Command + approval workflow | Automation that bypasses human sign-off |
| Concatenate display name | Ingestion transform | TypeQL function per query |
| Continuous event feed | Streaming adapter | Minute-polling batch job |

**Decision questions before building:**

1. Human judgment required? → command + workflow.
2. Data transformation at scale? → ingestion pipeline.
3. Reaction to an Ontology change? → automation/alert rule.
4. Computation over live graph state? → TypeQL function / metric DSL.
5. Recurring refresh? → scheduled job.

### Time Machine — our sanctioned pattern

History is never a second copy of an entity. The schema already encodes the correct pattern:

- `ledger-event` / `valuation-event` are append-only observations — each is a new instance, never an entity version.
- `supersedes` links a correction to its original; the original persists with provenance.
- `employment`, `ownership`, `commitment` carry `effective-date`/`end-date` — the relation instances *are* the history.
- Entity attributes hold current state only. If you need "what did the deal status look like on March 1" — that's `screening-decision` history and event-sourced stage transitions, not `deal-v2`.

### Action Sprawl — command design

Commands are named for business operations and declare their bundle: `record-capital-call` (creates `ledger-event`, attribution links, triggers recon) — not `insert-ledger-row`. Each command specifies required role, allowed prior states, side effects, audit payload, and whether approval gates it.

## Validation practice — task-based drills

Structural review (SHACL, TypeQL compile, PR checklist) proves the Ontology is coherent. It does not prove anyone can use it. Validation runs **real business questions** against the Ontology with participants — human and agent — who did not build it.

### Source questions from operations, not from the schema

Derive questions from the firm's operating rhythm — what partners ask Monday morning, what IR needs before LP calls, which answers currently require "the person who knows." Never derive them by inspecting what the Ontology already covers — that only validates existing coverage.

### Mesta-Asset drill sequences

Each sequence moves broad → granular: establish the situation, trace contributing factors, assess impact.

**Portfolio drill:**
1. Which fund has the weakest Net IRR this quarter?
2. Which sector drives that fund's underperformance?
3. Which investments in that sector carry the remaining cost?
4. Which portfolio companies sit behind those investments (look-through)?
5. Which of those companies show declining revenue or headcount signals?
6. Which capital calls or exits are scheduled against them this quarter?

**Deal pipeline drill:**
1. How many inbound prospects arrived this month and from which sources?
2. Which passed configured screening — and on which criteria?
3. Of those, which stalled in due diligence and why (unanswered DDQ items)?
4. Which screening passes were declined at IC — and were overrides used?
5. What evidence/citations support the surviving candidates' key claims?

**LP/commitment drill:**
1. Which LPs have the largest unfunded commitments?
2. Which funds hold those commitments, and who manages them?
3. What is the projected call schedule vs liquid coverage?
4. Which distributions are pending, and what events do they trace to?

**Provenance drill:**
1. Pick any IC report figure — trace to the Ontology entity, ledger event, and source document.
2. Which claims in last quarter's LP report came from AI extraction vs structured feeds?
3. Which of those claims had confidence below threshold, and who approved them?

### Running a drill

- **Participants:** include someone who did not build the Ontology — a builder-only drill validates memory, not design.
- **Tools:** general-purpose surfaces only (query bar, filters, drill-down). No purpose-built screen prepared for the question — that tests the app, not the model.
- **Record the path:** hesitation points, terms searched, wrong turns, manual exports/joins. Manual export is a signal: a real relationship exists that the Ontology lacks.
- **Show the work:** an answer without a demonstrable path doesn't count.

### Reading results

| Observation | Likely cause |
| --- | --- |
| Can't find a starting entity | Naming, missing aliases/descriptions — Misnomer |
| Different entities chosen for same concept | Duplication — Department Silos |
| Can't traverse an expected relationship | Missing/misnamed relation or role direction |
| Manual export + join performed | Relation or derived value missing from Ontology |
| Reasonable paths give different answers | Ambiguous granularity (fund vs investment vs deal), units, or aggregation |
| Only builders succeed | Source-system terminology leaked — System Silos |
| No path answers the question | Coverage gap — missing concept, data, or logic |

### People vs agents

Run the same questions through the control-panel agent after the human drill — no hints from the human path:

| Result | Interpretation |
| --- | --- |
| Both succeed | Model carries the context |
| Agent succeeds, humans struggle | Human-facing names/defaults need work |
| Humans succeed, agent struggles | Tribal knowledge not encoded in the Ontology — encode it (rules, descriptions, relations) |
| Both struggle | Missing data, relations, or semantics |

### Cadence

- Every Ontology SemVer change: run the question suite.
- Quarterly: introduce unseen questions to test generalization, not memorized paths.
- The question set is a regression suite for the Ontology — version it with the schema.

## Pragmatism

## Pragmatism

These are guides, not laws. Ship a reasonable Ontology over a perfect one. When taking a shortcut: name the tradeoff, state when it will matter, and add a `TODO(issue-id)`. Never cut corners on naming, semantic clarity, or security classification — those are hard to fix later.
