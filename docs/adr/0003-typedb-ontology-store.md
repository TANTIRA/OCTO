# ADR-0003: TypeDB as the Ontology and Graph Store

- Status: Proposed
- Date: 2026-09-21
- Risk tier: T2 (ontology schema ownership, second data store, cross-store consistency)
- Decision owner: CTO (ontology owner per `AGENTS.md`)
- Depends on: [ADR-0001](0001-platform-architecture.md), [ADR-0002](0002-self-hosted-supabase.md)

## Context

ADR-0001 deferred a dedicated graph store, keeping PostgreSQL as the single database. Two requirements have since sharpened:

- The investment Ontology is a first-class, versioned artifact — `AGENTS.md` defines ontology as code (OWL/SHACL plus TypeQL in Git) with CTO ownership.
- Look-through exposure, deal-sourcing relationships, extraction provenance, and AI grounding are inherently recursive and polymorphic — paths like `fund → investment → deal → operating company → subsidiary` with roles on both sides.

Recursive SQL can express this, but the Ontology is the product's canonical semantic layer: it needs typed entities/relations, role-based modeling, schema-level validation, and inference-friendly structure. `ontology/mesta-investment.tql` defines the initial TypeQL 3.0 schema.

The decision conflicts deliberately with ADR-0001's "one database" simplification; that ADR's intent — one source of truth per data class — is preserved by assigning distinct ownership, not by storing everything in one engine.

## Decision

**Adopt TypeDB 3 as the Ontology and graph store**, with `ontology/*.tql` schema versioned in Git per the `AGENTS.md` ontology rules (T2, SemVer via `owl:versionIRI`-equivalent tags, deprecation before deletion).

### Ownership split

| Store | Owns | Does not own |
| --- | --- | --- |
| Supabase PostgreSQL | Append-only IBOR ledger events, workflow state, approvals, audit log, auth/session data, report artifacts, projection tables for dashboards | Canonical entity-relationship graph |
| TypeDB | Canonical entities, relations, ownership edges, screening decisions, document/claim provenance graph, deal pipeline state as ontology facts | Financial event ledger of record |

### Synchronization

- `ingestion` writes each validated source record into both stores under one logical transaction coordinated by the application: ledger facts → PostgreSQL; entity/relation graph → TypeDB.
- Every TypeDB entity carries `external-id` attributes referencing its PostgreSQL origin; every PostgreSQL record that produces graph state records the TypeDB IID.
- `recon` gains a second responsibility: graph-ledger reconciliation — every IBOR event must have attributed entities in the graph, and every graph position must trace to ledger events.
- TypeDB writes flow through the same SHACL pre-validation as PostgreSQL writes; a write that fails either store is rejected as a unit.

### TypeQL usage rules

- Schema lives in `ontology/` and deploys via the migration pipeline — never ad-hoc in Studio/Console.
- TypeQL 3.0 syntax only: `entity`/`relation`/`attribute` kinds, `let` for computed values, `select` (not `get`), pipeline queries ending in `fetch`, relation syntax `$rel isa relation-type (role: $player)`.
- `integer`, `decimal`, `datetime`/`datetime-tz`, `date` — no `long`; money uses `decimal`.
- Cardinalities are explicit where the default is wrong: `owns`/`relates` default `@card(0..1)`, `plays` defaults `@card(0..)`.
- Reserved identifiers (`with`, `match`, `fetch`, `define`, `isa`, `of`, `from`, `in`, `first`, `last`, etc.) are never used as labels.
- Application queries go through `modules/api` service code — no client-supplied TypeQL reaches the database.

## Consequences

### Positive

- Look-through, provenance, and relationship queries are native rather than recursive-CTE emulation.
- Role-based relations model PE structures precisely (commitment: investor + vehicle; ownership: owner + asset).
- TypeQL functions and polymorphic queries support screening rules and AI grounding over a typed graph.
- Ontology versioning aligns with the existing `AGENTS.md` ontology governance.

### Negative

- Two stores to operate, back up, monitor, and keep consistent — the cost ADR-0001 tried to defer.
- Cross-store consistency is application-managed; `recon` must cover graph-vs-ledger drift.
- TypeDB is a younger ecosystem; driver/ORM maturity and operational tooling require evaluation before production.
- Team must learn TypeQL semantics (e.g. relation role syntax inversion vs 2.x, no NULLs, `try` for optionality).

### Neutralized risks

- PostgreSQL remains ledger of record: if the graph store is unavailable or drifts, financial truth is unaffected.
- Rebuild path: the graph is reconstructable from ledger + staging by replaying ingestion.
- Exit path: ontology stays in Git as TypeQL; migration to another graph engine loses engine-specific features but not the model.

## Acceptance criteria

- [ ] `ontology/mesta-investment.tql` validates against a live TypeDB 3 instance in CI
- [ ] Dual-write ingestion path with atomic failure semantics implemented
- [ ] Graph-ledger reconciliation report passes on seeded test data
- [ ] TypeDB backup/restore and upgrade runbooks exist
- [ ] Performance test: look-through aggregation over 5-level hierarchy within reporting SLA
