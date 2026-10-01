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
- TypeQL `entity` maps to a node label; `sub` maps to multiple labels (a subtype instance carries every ancestor label). `@key`/`@unique` map to `IS UNIQUE` constraints.
- Two-role relations map to relationship types; relations with three or more roles are reified as nodes with labeled edges.
- Constraints Neo4j cannot express (`@values`, `@regex`, `@range`, `@card`) are preserved as machine-readable `// type: ... | name: ... | ...` comment lines and enforced by SHACL at write time.
- Application queries go through `modules/api` service code — no client-supplied Cypher reaches the database.

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
- [ ] Dual-write ingestion path with atomic failure semantics implemented
- [ ] Graph-ledger reconciliation report passes on seeded test data
- [x] Neo4j backup/restore and upgrade runbooks exist (`restore-runbook.md` §6, `runbooks/neo4j-upgrade.md`, #308)
- [x] Performance test: look-through aggregation over 5-level hierarchy within reporting SLA (`modules/lookthrough/src/test/kotlin/com/octo/lookthrough/ExposurePerfTest.kt`, #308)
