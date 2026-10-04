# ADR-0004: Neo4j as the Graph Store

- Status: Proposed
- Date: 2026-11-24
- Risk tier: T2 (ontology schema ownership, second data store, cross-store consistency)
- Decision owner: CTO (ontology owner per `AGENTS.md`)
- Supersedes: [ADR-0003](0003-typedb-ontology-store.md)
- Depends on: [ADR-0001](0001-platform-architecture.md), [ADR-0002](0002-self-hosted-supabase.md)
- Issue: #189

## Context

ADR-0003 adopted TypeDB 3 as the ontology and graph store, with `ontology/octo-investment.tql` as the schema of record. Since then:

- No production code ever opened a TypeDB connection — the only coupling was the ontology module's CI gate applying the `.tql` schema to a TypeDB Testcontainer, plus dead `TYPEDB_*` env vars.
- Production already runs a Neo4j Community service (`octo-neo4j-db`, Dokploy) for another workload, so Neo4j operations (backup, monitoring, upgrade) are in-house rather than aspirational.
- TypeDB 3 is the younger, less operationally mature dependency ADR-0003 flagged as a risk; the team's operational path of least resistance is the store already deployed.

## Decision

**Adopt Neo4j Community Edition as the ontology and graph store**, with `ontology/octo-investment.cypher` as the schema of record. The TypeQL schema, TypeQL parser, and TypeDB CI gate are removed; OWL/SHACL artifacts and their drift guard are unchanged.

### Ownership split

Unchanged from ADR-0003: Supabase PostgreSQL owns the append-only IBOR ledger, workflow state, and projections; the graph store owns canonical entities, relations, ownership edges, and provenance.

### Cypher usage rules

- Schema lives in `ontology/octo-investment.cypher` and deploys via the migration pipeline — never ad-hoc in Browser.
- Target is Community Edition: `IS UNIQUE` constraints only. `NODE KEY`, property-existence, and relationship-type constraints are Enterprise-only and must not appear in the schema file.
- TypeQL `entity` maps to a node label; `sub` maps to multiple labels (a subtype instance carries every ancestor label). `@key`/`@unique` map to `IS UNIQUE` constraints — composite `(tenantId, <key>)` on tenant-owned entities, global on reference data (see *Tenant-scoped keys* below).
- Two-role relations map to relationship types; relations with three or more roles are reified as nodes with labeled edges.
- Constraints Neo4j cannot express (`@values`, `@regex`, `@range`, `@card`) are preserved as machine-readable `// type: ... | name: ... | ...` comment lines and enforced by SHACL at write time.
- Application queries go through `modules/api` service code — no client-supplied Cypher reaches the database.

### Dual-write: transactional outbox (amendment, #308)

PostgreSQL stays the ledger of record and the graph stays derived, so graph writes never join a ledger transaction:

- **Not 2PC.** Neo4j Community has no XA participant, and a coordinator would make every ledger write depend on graph availability.
- **Not "write Postgres, then Neo4j".** A crash or a Neo4j error between the two loses the graph update with nothing left to retry it.
- **Transactional outbox.** The store call that writes a domain row also inserts an `octo.graph_outbox` row on the same scoped connection, so both commit or roll back together. A projector in `modules/api` claims pending rows (`for update skip locked` plus a lease, the `report_job` pattern) and applies each one with an idempotent `MERGE` keyed by `octoId`. Retries back off. Rows that keep failing become `failed`, are counted in metrics, and are reported — never skipped.
- **"Atomic failure semantics"** therefore means the ledger write and the intent to update the graph are one fact. If Neo4j is down, ledger writes still succeed and the outbox drains on recovery. API readiness does not depend on the graph.
- **Reconciliation** compares the source tables with the graph per tenant — missing, stale, orphaned, stuck — and opens evidence-request tasks for discrepancies. It is what proves the graph never diverges undetected.

### Tenant-scoped keys (amendment, #308 — ontology 2.0.0)

The graph holds every tenant in one database (Community Edition has a single user database), so a global `@key` makes one tenant's data block another's: two tenants registering the same fund would collide. Tenant-owned entities (`party` and its subtypes, `fund`, `deal`, `investment`, `document`, `wallet`, `evm-wallet`) therefore own `tenant-id` and `octo-id`:

- Their business key is the composite `(tenantId, <key>) IS UNIQUE`, which Community Edition supports.
- `octoId` — the Postgres row id, or for superseding rows the lineage-root id, so a correction updates the same node — is globally `IS UNIQUE`.
- Reference data (`sector`, `country`, `instrument` and its mints/contracts) keeps global keys.

Every graph read filters on `tenantId`. This changes what `@key` means for those entities, so the ontology moves to **2.0.0** (SemVer MAJOR). Existing graphs drop the replaced global constraints (`fund_legal_name_key`, `deal_display_name_key`, `investment_display_name_key`, `document_file_name_key`, `person_email_unique`, `wallet_solana_address_key`, `evm_wallet_evm_address_key`) before applying the schema. Production holds no projected data yet, so nothing needs to be rekeyed.

### Instrument-flow projection (#565, ontology 2.1.0)

Promoted `instrument_flow` rows enqueue four upserts in the same transaction as the ledger insert: the global `:Instrument` (merged on `instrumentId`), the tenant's `:Wallet` or `:EvmWallet`, the `:InstrumentFlow` (octoId = lineage root), and the reified `:InstrumentFlowOf` with `FLOW_SIDE`, `INSTRUMENT_SIDE`, and `WALLET_SIDE` edges. Relationship types come from the projection table, not the payload. `instrument-flow` and `instrument-flow-of` own `tenant-id` and a unique `octo-id`. A wallet's octoId is the name-based UUID of `(tenant, chain, address)`, because `tracked_address` has no row uuid. A flow whose wallet has no tenant stays in the ledger and is not projected.

## Consequences

### Positive

- One graph engine to operate — the one already running in production, with an established backup and monitoring path.
- Cypher has a far larger hiring pool, driver maturity, and tooling ecosystem than TypeQL.
- The dual-format comment convention keeps the schema file both executable Cypher and machine-parseable metadata, so the OWL/SHACL drift guard survives without a second schema language.

### Negative

- Neo4j Community cannot natively enforce role/cardinality semantics TypeQL expressed (`@card`, n-ary relations, polymorphic `plays`) — validation depends on SHACL pre-checks and the reified-node convention being applied correctly by writers.
- `octo.asset.typedb_iid` and the `typedbIid` field kept their names at decision time; V25 (`#189`) has since renamed them to `graph_node_id`/`graphNodeId` — the column stores the graph element id regardless of engine.
- Typed-relation inference TypeDB offered (rule-based reasoning inside the store) moves to application code or SHACL rules.

### Neutralized risks

- PostgreSQL remains ledger of record: if the graph store is unavailable or drifts, financial truth is unaffected.
- Rebuild path unchanged: the graph is reconstructable from ledger + staging by replaying ingestion.
- OWL/SHACL artifacts are engine-neutral; a future engine swap loses Cypher-specific constraints but not the model.

## Acceptance criteria

- [x] `ontology/octo-investment.cypher` validates against a live Neo4j Community instance in CI (`Neo4jSchemaIT`, pinned image `neo4j:2025.12.1-community`)
- [x] Dual-write ingestion path with atomic failure semantics implemented — transactional outbox (V45, #559) drained by `GraphProjector` (#560); `GraphOutboxStoreIT`, `GraphProjectionIT`. Promoted `instrument_flow` is the first live projection (#565): the flow, its `:InstrumentFlowOf` edges, and the `:Wallet` / `:EvmWallet` / `:Instrument` endpoints.
- [x] Graph-ledger reconciliation report passes on seeded test data — `GraphReconciler` (#308): missing, stale, orphan, forked, failed and stuck, per tenant; `GraphReconciliationIT`
- [x] Neo4j backup/restore and upgrade runbooks exist (`restore-runbook.md` §6, `runbooks/neo4j-upgrade.md`, #308)
- [x] Performance test: look-through aggregation over 5-level hierarchy within reporting SLA (`modules/lookthrough/src/test/kotlin/com/octo/lookthrough/ExposurePerfTest.kt`, #308)
