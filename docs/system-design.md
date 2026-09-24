# System Design

This document gathers the Mesta-Asset system design into one place: High-Level Architecture, High-Level Design, Low-Level Design, and the end-to-end architecture. The ADRs stay the source of each decision. This document shows how the decisions fit together, and each section links back to the ADR it relies on.

**Status legend** used in the tables and diagram notes:

| Mark | Meaning |
| --- | --- |
| ✅ | Implemented on `main` |
| 🟡 | Implemented in an open draft PR |
| ⬜ | Planned; tracked in [#6](https://github.com/TANTIRA/Mesta-Asset/issues/6), design not yet agreed |

Sources: [ADR-0001](adr/0001-platform-architecture.md), [ADR-0002](adr/0002-self-hosted-supabase.md), [ADR-0003](adr/0003-typedb-ontology-store.md), [AI architecture](ai-architecture.md), [decision-model integration map](decision-model-integration-map.md), [quantitative methodology](quantitative-methodology.md), [data security and governance](data-security-governance.md), `infra/docker-compose.yml`.

---

## 1. High-Level Architecture

The runtime topology. It shows one deployable application, two data stores with separate ownership, and external services reached only from the application tier.

```mermaid
flowchart TB
  subgraph USERS["Users and consumers"]
    AN["Analysts, investment and ops teams"]
    LP["LP / GP external users"]
    XL["Excel and external systems"]
  end

  subgraph EDGE["Edge — public"]
    RP["Reverse proxy — TLS, rate limits (Dokploy)"]
  end

  subgraph APP["Application tier — private network 'app'"]
    WEB["mesta-web — SPA"]
    API["mesta-api — Kotlin / Spring Boot modular monolith"]
  end

  subgraph DATA["Data tier — private network 'data'"]
    PG[("Supabase PostgreSQL — IBOR ledger, workflow, audit, decision staging")]
    AUTH["Supabase Auth — JWT issuer + JWKS"]
    ST[("Supabase Storage — documents and artifacts, private buckets")]
    TDB[("TypeDB 3 — ontology graph, attribution, ownership, provenance")]
  end

  subgraph EXT["External services"]
    SRC["Source systems — CRMs, fund admins, market data, filings"]
    LLM["Decision model — OpenRouter (Public / Internal data only)"]
    SHM["Approved self-hosted model — Confidential data"]
  end

  subgraph OPS["Operations"]
    OTEL["OpenTelemetry collector — metrics, traces, audit sink"]
    BAK[("Encrypted backups + WAL archive")]
    SEC["Secret manager"]
  end

  AN & LP --> RP
  XL --> RP
  RP --> WEB
  RP -->|"/api — bearer JWT"| API
  WEB -->|login| AUTH
  API -->|verify JWT via JWKS| AUTH
  API --> PG
  API --> ST
  API --> TDB
  API -->|ingestion adapters| SRC
  API -->|"classification guard: Public / Internal only"| LLM
  API -.->|"Confidential (planned)"| SHM
  API --> OTEL
  PG --> BAK
  ST --> BAK
  SEC -.->|runtime env| API
```

| Component | Technology | Owner | Status |
| --- | --- | --- | --- |
| `mesta-api` | Kotlin 2.2, Java 21, Spring Boot 3.5, Gradle | Mesta-Asset team | ✅ scaffold, auth boundary, migrations |
| `mesta-web` | Separate frontend image (ADR-0001 §6) | Mesta-Asset team | ⬜ |
| PostgreSQL, Auth, Storage | Self-hosted Supabase (ADR-0002) | Platform / DevOps | ✅ compose, override |
| TypeDB | TypeDB 3, schema `ontology/mesta-investment.tql` (ADR-0003) | CTO (ontology) | ✅ schema + CI validation |
| Decision model | `typesafe/jev-1.13` via OpenRouter | AI Tech Lead | ✅ client, 2 decision points |
| Backup / PITR | Operator-provided (ADR-0002) | Platform / DevOps | ⬜ runbook |

---

## 2. High-Level Design

### 2.1 Module map and dependency rules

One deployable with Gradle-enforced module boundaries. `ModuleBoundaryTest` enforces the allowed arrows below in CI. Adding an arrow means changing that test in the same PR.

```mermaid
flowchart LR
  API["api — REST boundary, auth, composition root"]
  subgraph DOMAIN["Domain modules"]
    IBOR["ibor-core — ledger derivation"]
    LT["lookthrough — exposure aggregation"]
    ANA["analytics — metrics engine"]
    REC["recon — source vs IBOR"]
    WF["workflow — tasks, approvals"]
    DS["deal-sourcing — screening, DDQ, IC"]
    ING["ingestion — adapters, extraction, staging"]
    CP["control-panel — AI apps, judgment client"]
    ONT["ontology — OWL/SHACL + TypeQL validation"]
  end

  API --> IBOR & LT & ANA & REC & WF & DS & ING & CP
  ING --> CP
```

Rules:

- `api` is the only composition root, and no module depends on it.
- Domain modules are closed to each other. The only exception so far is `ingestion → control-panel`, for the judgment client. Glue between modules, such as `ibor-core` output feeding `analytics` input, lives in `api`.
- Vendor formats stay inside `ingestion` adapters (ADR-0001 consequences).

| Module | Responsibility | Tier | Status |
| --- | --- | --- | --- |
| `ibor-core` | Supersession-resolved ledger, commitment positions, investor cash flows | T2 | 🟡 [#11](https://github.com/TANTIRA/Mesta-Asset/issues/11) |
| `analytics` | DPI, RVPI, TVPI, XIRR, KS-PME, Direct Alpha, commitment status | T2 | 🟡 [#9](https://github.com/TANTIRA/Mesta-Asset/pull/9) |
| `lookthrough` | Path-sum exposure; gross, net, long and short measures | T2 | 🟡 [#10](https://github.com/TANTIRA/Mesta-Asset/pull/10) |
| `ingestion` | Document classification, claim support, append-only decision staging | T2 | ✅ |
| `control-panel` | Judgment client, data-classification guard | T2 | ✅ |
| `ontology` | SHACL validation, TypeQL ↔ OWL drift checks | T2 | ✅ |
| `api` | JWT resource server, Flyway, actuator | T2 (auth) | ✅ |
| `recon`, `workflow`, `deal-sourcing` | — | T2 | ⬜ |

### 2.2 Data ownership

Each data class has exactly one owner (ADR-0003). This is how "one source of truth" still holds with two stores.

```mermaid
flowchart LR
  subgraph PGO["PostgreSQL owns — facts over time"]
    L["Ledger events (append-only)"]
    D["Decision staging (append-only)"]
    W["Workflow state, approvals, audit"]
    R["Report artifacts, projections"]
  end
  subgraph TDO["TypeDB owns — entities and relationships"]
    E["Funds, LPs, GPs, companies, deals, investments"]
    A["cash-flow-attribution — event to vehicle / LP / position"]
    O["ownership edges — look-through"]
    P["Document and claim provenance"]
  end
  L -.-|"event id / external-id"| A
  A -.->|"event ids per commitment"| DER["ibor-core derivation"]
  L --> DER
  O --> LTX["lookthrough"]
```

Consequence: `ibor-core` never stores attribution. The caller resolves the event ids that belong to a commitment from TypeDB and passes them to the derivation (see §3.3).

### 2.3 Capability map against the benchmarks

| Benchmark feature | Mesta-Asset capability | Module | Status |
| --- | --- | --- | --- |
| Aladdin IBOR — "one database, one system, one process" | Append-only ledger; positions derived, never written | `ibor-core` | ✅ ledger · 🟡 derivation |
| Aladdin Performance & Attribution | PE performance (§2); Brinson (§4.2) | `analytics` | 🟡 · ⬜ |
| Aladdin look-through | Path-sum exposure (§7.2) | `lookthrough` | 🟡 |
| Aladdin post-trade compliance | Rules evaluated after ledger writes → workflow tasks | `recon` + `workflow` | ⬜ |
| Aladdin reconciliation / trade matching | Source vs IBOR, graph vs ledger | `recon` | ⬜ |
| Aladdin risk (VaR/ES, factor) | Methodology §3.4, §4.1 | `analytics` | ⬜ |
| Marquee Asset service | Asset master using ontology types (owner decision on #6) | `api` + TypeDB | ⬜ |
| Marquee Data service | Bi-temporal time series (`effective_date` + `recorded_at`) | `ingestion` + `api` | ⬜ |
| Marquee Report service | Report jobs over the analytics engines | `analytics` + `workflow` + `api` | ⬜ |
| Palantir AIP workflows | Evaluated agents behind a tool allowlist | `deal-sourcing`, `control-panel` | ✅ 2 decision points · ⬜ rest |

### 2.4 Key design decisions

| # | Decision | Reason | Source |
| --- | --- | --- | --- |
| 1 | Modular monolith | Avoids rebuilding the tool fragmentation the product exists to remove | ADR-0001 §1 |
| 2 | IBOR derived, never written | Auditable and reconcilable; corrections are new rows | ADR-0001 §3, `V1__init.sql` trigger |
| 3 | Append-only tables enforced by trigger | The database, not the app, blocks UPDATE/DELETE | `V1`, `V2` migrations |
| 4 | Ledger amounts are investor-signed | Contributions negative, distributions positive (methodology §10.2) | Owner decision, #6 |
| 5 | Fees, expenses, carry and other income are outside the investor series | They stay on the position report and out of IRR and multiples | Owner decision, #6 |
| 6 | Undefined metric results are `null`, never 0 | Methodology §10.7 | #9 |
| 7 | Attribution lives in TypeDB | One owner per data class | ADR-0003 |
| 8 | Confidential data never reaches a public model | `DataClassification.mayLeavePlatform` guard | AGENTS.md, `control-panel` |
| 9 | Self-hosted Supabase; backups and PITR are the operator's job | Data residency and control | ADR-0002 |

### 2.5 Security boundary

```mermaid
flowchart LR
  C["Client"] -->|"HTTPS"| RP["Reverse proxy"]
  RP -->|"Authorization: Bearer JWT"| SC["SecurityConfig — stateless resource server"]
  SC -->|"/actuator/health, /actuator/info"| PUB["public"]
  SC -->|"everything else"| AZ["authenticated — fails closed without JWKS"]
  AZ --> SVC["Module services — RBAC / ABAC (planned)"]
  SVC -->|"non-superuser service role"| PG[("PostgreSQL")]
  SVC -->|"ClassifiedState"| G{"mayLeavePlatform?"}
  G -->|"Public / Internal"| LLM["OpenRouter decision model"]
  G -->|"Confidential / Strictly Confidential"| X["ConfidentialStateRejectedException"]
```

---

## 3. Low-Level Design

### 3.1 PostgreSQL schema (`mesta`)

Tables created by the migrations that already exist. Every table rejects UPDATE and DELETE with a trigger, and every row carries provenance.

```mermaid
erDiagram
  LEDGER_EVENT {
    uuid id PK
    text external_id "unique with source_system"
    text flow_type "7 ontology values"
    numeric monetary_amount "investor-signed"
    char currency_code "ISO 4217"
    timestamptz occurred_at
    timestamptz recorded_at
    uuid supersedes_id FK
    text rationale "required when superseding"
    text source_system
    text actor
    uuid ingestion_run_id
    uuid correlation_id
  }
  DOCUMENT_CLASSIFICATION {
    uuid id PK
    char document_sha256
    text document_type "9 ontology values"
    double confidence "0..1"
    jsonb distribution
    boolean requires_review
    text model_version
    uuid supersedes_id FK
    text source_system
    uuid correlation_id
  }
  CLAIM_ASSESSMENT {
    uuid id PK
    text claim_text
    char source_document_sha256
    double support_probability
    double support_threshold
    double review_band
    boolean supported
    boolean requires_review
    text model_version
    uuid supersedes_id FK
    uuid correlation_id
  }
  LEDGER_EVENT ||--o| LEDGER_EVENT : "supersedes"
  DOCUMENT_CLASSIFICATION ||--o| DOCUMENT_CLASSIFICATION : "supersedes"
  CLAIM_ASSESSMENT ||--o| CLAIM_ASSESSMENT : "supersedes"
```

Invariants enforced in the database:

- `flow_type` and `document_type` check constraints mirror the ontology `@values`. Drift tests in `ingestion` and `ibor-core` fail CI if the two diverge.
- A superseding row must carry a rationale and cannot supersede itself.
- `(source_system, external_id)` is unique, so a replayed source record cannot be inserted twice.

### 3.2 Ledger event lifecycle

```mermaid
stateDiagram-v2
  [*] --> Recorded: insert (append-only)
  Recorded --> Current: visible at knownAt >= recorded_at
  Current --> Superseded: a later row sets supersedes_id to this id
  Superseded --> [*]: stays in the table, excluded from derivation
  note right of Superseded
    UPDATE and DELETE raise restrict_violation.
    Two current rows superseding one original is rejected (fork).
  end note
```

### 3.3 `ibor-core` 🟡

```mermaid
classDiagram
  class FlowType {
    <<enum>>
    CONTRIBUTION
    DISTRIBUTION
    RECALLABLE_DISTRIBUTION
    MANAGEMENT_FEE
    EXPENSE
    CARRIED_INTEREST
    OTHER_INCOME
    +String wireValue
    +Boolean investorFlow
  }
  class LedgerEvent {
    +UUID id
    +FlowType flowType
    +BigDecimal amount
    +Currency currency
    +Instant occurredAt
    +Instant recordedAt
    +UUID supersedesId
  }
  class CommitmentPosition {
    +Currency currency
    +BigDecimal called
    +BigDecimal distributed
    +BigDecimal recallableDistributed
    +BigDecimal fees
    +BigDecimal expenses
    +BigDecimal carriedInterest
    +BigDecimal otherIncome
    +List cashFlows
  }
  class Ledger {
    <<functions>>
    +currentEvents(ledger, knownAt) List~LedgerEvent~
    +commitmentPosition(ledger, members, knownAt, zone) CommitmentPosition
  }
  LedgerEvent --> FlowType
  Ledger ..> LedgerEvent
  Ledger ..> CommitmentPosition
```

Derivation algorithm:

1. **Bi-temporal cut.** Keep only events with `recordedAt <= knownAt`.
2. **Resolve supersession over the whole ledger.** Any event targeted by a kept event is dropped. A missing target, or two events targeting the same original, is an error.
3. **Filter to `members`.** These are the event ids attributed to the commitment in TypeDB. A correction that moves an event to another commitment therefore removes it from this one.
4. **Aggregate.** Take a positive total per flow type. The single-currency check fails loudly on mixed currencies.
5. **Build the investor series.** Keep only flow types with `investorFlow = true`. Date each flow in the zone the caller passes, then sort.

### 3.4 `analytics` 🟡

```mermaid
classDiagram
  class CashFlow {
    +LocalDate date
    +BigDecimal amount
  }
  class CashFlowSeries {
    +Currency currency
    +List~CashFlow~ flows
    +BigDecimal nav
    +LocalDate valuationDate
    +paidIn() BigDecimal
    +distributed() BigDecimal
  }
  class PerformanceReport {
    +BigDecimal dpi
    +BigDecimal rvpi
    +BigDecimal tvpi
    +Double irr
    +String methodology
  }
  class CommitmentStatus {
    +BigDecimal committed
    +BigDecimal called
    +BigDecimal unfunded
    +BigDecimal drawdownRate
    +BigDecimal distributionRate
  }
  class Performance {
    <<functions>>
    +performance(series) PerformanceReport
    +xirr(flows) Double
    +ksPme(series, benchmark) BigDecimal
    +directAlpha(series, benchmark) Double
    +commitmentStatus(committed, series) CommitmentStatus
  }
  CashFlowSeries *-- CashFlow
  Performance ..> CashFlowSeries
  Performance ..> PerformanceReport
  Performance ..> CommitmentStatus
```

- **XIRR.** Uses actual/365. The NPV is scanned on a 401-point grid over (−99%, +10,000%); the result is defined only if the NPV crosses zero exactly once, and is then refined by bisection. Several roots give `null`.
- **Guards.** Cash flows after `valuationDate` are rejected, and NAV must not be negative. A zero denominator returns `null`.

### 3.5 `lookthrough` 🟡

```mermaid
classDiagram
  class OwnershipEdge {
    +String holder
    +String held
    +BigDecimal fraction
  }
  class ExposureReport {
    +String root
    +Currency currency
    +Map byAsset
    +netExposure() BigDecimal
    +grossExposure() BigDecimal
    +longExposure() BigDecimal
    +shortExposure() BigDecimal
    +assetCount() Int
  }
  class Exposure {
    <<functions>>
    +lookThrough(root, rootNav, currency, edges) ExposureReport
  }
  Exposure ..> OwnershipEdge
  Exposure ..> ExposureReport
```

The function walks depth-first from the root, multiplying ownership fractions, and adds the result at each leaf. Because every path is summed, a company held through two funds is counted through both. A node already on the current path raises a cycle error.

### 3.6 AI decision points (`control-panel` + `ingestion`) ✅

```mermaid
classDiagram
  class JudgmentClient {
    <<interface>>
    +decide(state, questions) JudgmentResult
  }
  class OpenRouterDecisionsClient
  class ClassifiedState {
    +DataClassification classification
    +Any payload
  }
  class DocumentClassifier {
    +classify(state) DocumentClassification
  }
  class ClaimSupportAssessor {
    +assess(state) ClaimSupport
  }
  class DocumentClassificationStore {
    <<interface>>
  }
  class ClaimAssessmentStore {
    <<interface>>
  }
  class JdbcDecisionStore
  JudgmentClient <|.. OpenRouterDecisionsClient
  DocumentClassifier --> JudgmentClient
  ClaimSupportAssessor --> JudgmentClient
  DocumentClassificationStore <|.. JdbcDecisionStore
  ClaimAssessmentStore <|.. JdbcDecisionStore
  DocumentClassifier ..> ClassifiedState
```

### 3.7 Planned contracts ⬜

These are proposals only. Per CLAUDE.md, each needs its plan agreed on #6 before any code is written, and none of these endpoints exist yet.

| Slice | Surface | Shape, following the Marquee reference |
| --- | --- | --- |
| 4b | `JdbcLedgerReader` + `api` glue | `CommitmentPosition` → `CashFlowSeries` → `performance()` |
| 5 | Asset master | Types limited to ontology entities; identifiers held as xrefs |
| 6 | Time-series data | Query by dataset, date range, fields, `asOfTime`, `since`; bi-temporal, append-only |
| 7 | Report jobs | Type, position source, measures; status `new → executing → done / error` |

---

## 4. End-to-End Architecture

### 4.1 System flow

Source data to governed output. Every write passes validation, and every outbound artifact passes a human approval gate.

```mermaid
flowchart LR
  subgraph IN["Ingest"]
    S1["Structured sources"] --> AD["Adapters"]
    S2["Documents"] --> CL["Classify + extract (decision model)"]
    AD --> STG[("Staging")]
    CL --> DST[("Decision staging")]
  end

  subgraph GOV["Validate"]
    SH["SHACL + ontology checks"]
    RV["Human review queue"]
  end

  subgraph REC["Record"]
    LED[("Ledger — PostgreSQL")]
    GR[("Graph — TypeDB")]
  end

  subgraph DER["Derive"]
    POS["ibor-core — positions, investor flows"]
    PERF["analytics — DPI / TVPI / IRR / PME"]
    EXP["lookthrough — exposure"]
  end

  subgraph OUT["Serve"]
    APIX["REST API"]
    UIX["Web UI / Excel"]
    RPT["Reports and LP artifacts"]
    APR["Approval gate"]
  end

  STG --> SH
  DST -->|"requires_review"| RV
  RV --> SH
  SH -->|"facts"| LED
  SH -->|"entities, attribution, ownership"| GR
  LED --> POS
  GR -->|"event ids per commitment"| POS
  POS --> PERF
  GR -->|"ownership edges"| EXP
  PERF & EXP --> APIX --> UIX
  PERF & EXP --> RPT --> APR -->|"approved"| DIST["LPs / external recipients"]
  SH -->|"rejects, breaks"| RC["recon → workflow tasks"]
```

### 4.2 Sequence: from ledger to fund performance (slices 4a + 4b + 1)

```mermaid
sequenceDiagram
  autonumber
  actor U as Analyst
  participant API as api
  participant TDB as TypeDB
  participant PG as PostgreSQL
  participant IB as ibor-core
  participant AN as analytics

  U->>API: GET fund performance (JWT)
  API->>API: verify JWT, check permissions
  API->>TDB: event ids attributed to commitment (LP, fund)
  TDB-->>API: members
  API->>PG: ledger events (supersession closure)
  PG-->>API: rows
  API->>IB: commitmentPosition(ledger, members, knownAt, zone)
  IB-->>API: CommitmentPosition
  Note over API: NAV from valuation-event, store not decided yet
  API->>API: NAV, as-of date
  API->>AN: performance(CashFlowSeries)
  AN-->>API: PerformanceReport (null = undefined)
  API-->>U: DPI, RVPI, TVPI, IRR + methodology version
```

### 4.3 Sequence: document ingestion decision (implemented)

```mermaid
sequenceDiagram
  autonumber
  participant ING as ingestion
  participant G as OpenRouterDecisionsClient (classification guard)
  participant DM as Decision model (OpenRouter)
  participant PG as PostgreSQL

  ING->>ING: wrap payload in ClassifiedState
  ING->>G: decide(state, questions)
  alt Confidential or Strictly Confidential
    G-->>ING: ConfidentialStateRejectedException
  else Public or Internal
    G->>DM: typed question (choice / noul)
    DM-->>G: answer + probability distribution
    G-->>ING: JudgmentResult + DecisionLineage
    ING->>ING: map to DocumentType / ClaimSupport, set requires_review
    ING->>PG: insert document_classification / claim_assessment (append-only)
  end
```

### 4.4 Sequence: deal sourcing to portfolio (planned)

```mermaid
sequenceDiagram
  autonumber
  actor A as Analyst
  participant DS as deal-sourcing
  participant DM as Decision model
  participant WF as workflow
  actor IC as Investment Committee
  participant IB as ibor-core ledger

  A->>DS: upload pitch deck
  DS->>DM: screen against versioned criteria
  DM-->>DS: Pass / Conditional / Fail / Unknown / Conflicting + distribution
  DS->>A: recommendation + evidence gaps
  A->>DS: accept or override (rationale recorded)
  DS->>WF: IC report draft for approval
  WF->>IC: approval task
  IC-->>WF: approve
  WF->>IB: convert prospect: first ledger events (append-only)
```

### 4.5 Delivery pipeline

```mermaid
flowchart LR
  I["GitHub issue + risk tier"] --> P{"T2 or higher?"}
  P -->|"yes"| PL["Plan agreed on the issue"]
  P -->|"no"| B
  PL --> B["Branch feat/issue-slug, under 400 lines"]
  B --> CK["./gradlew check test — ktlint, kover 70%, SHACL, Testcontainers ITs"]
  CK --> PR["PR — SCQA, tier, evidence, rollback, ai-assisted label"]
  PR --> RV["Review — T2: 2 reviewers incl. Tech Lead + security"]
  RV --> M["Squash merge (human)"]
  M --> STG["Staging — 24h for T2"]
  STG --> PRD["Production (human deploy)"]
```

Agents never merge, deploy, or push to `main` (AGENTS.md).
