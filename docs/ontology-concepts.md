# Ontology Concepts — Reference Mapping

How the canonical ontology-platform concepts map onto Mesta-Asset's implementation. The reference model describes a digital-twin Ontology: object types, properties, link types, action types, roles, functions, interfaces, and object views. Mesta-Asset implements the same concepts on vendor-neutral technology: TypeDB/TypeQL 3.0 for the semantic graph, PostgreSQL for the ledger, OWL/SHACL for formal validation, and Kotlin services for behavior.

## Concept map

| Reference concept | Mesta-Asset equivalent | Where it lives |
| --- | --- | --- |
| Ontology (digital twin) | Investment Ontology — canonical model of prospects, funds, companies, LPs, GPs, documents, and events | `ontology/mesta-investment.tql` + OWL/SHACL in Git |
| Object type | TypeQL `entity` type (e.g. `fund`, `deal`, `operating-company`) | `ontology/` schema |
| Object | Entity instance | TypeDB instance |
| Object set | Query result set / typed collection | TypeQL `match` results |
| Property | TypeQL `attribute` type + `owns` declaration (e.g. `owns committed-amount`) | `ontology/` schema |
| Property value | Attribute value on an instance | TypeDB data |
| Shared property | Attribute type owned by multiple entity types (e.g. `external-id`, `effective-date`, `currency-code`) | `ontology/` schema — same attribute label reused |
| Link type | TypeQL `relation` type with `relates` roles (e.g. `commitment`, `ownership`, `fund-investment`) | `ontology/` schema |
| Link | Relation instance `links (role: $player)` | TypeDB instance |
| Action type | Application command — a typed, permission-checked, audited mutation delivered through `api` + `workflow` (e.g. `advanceDealStage`, `recordValuation`, `approveReport`) | `modules/api`, `modules/workflow` |
| Roles (permissioning) | RBAC + ABAC scoped by organization/fund/deal/entity/document/field/action | `modules/api` policy layer |
| Functions | TypeQL functions (`with fun`/`define fun`) for graph computation; Kotlin domain services and the metrics DSL for financial math | `ontology/` + `modules/analytics` |
| Interfaces (polymorphism) | TypeQL type hierarchies — `party @abstract` → `organization` → `fund-manager`/`limited-partner`/`operating-company` — inherited attributes and role-playing | `ontology/` schema |
| Object View | Entity dossier screens — Company Details, Fund Metrics, prospect detail — the 360° hub for one entity | `user-interfaces.md` |
| Dataset → object type analogy | Source adapter mapping: each vendor dataset maps to entity types + attributes | `modules/ingestion` mappings |
| Join → link type analogy | Relations replace foreign-key joins; links are first-class and traversable | TypeDB |

## Semantic differences that matter

| Reference behavior | Mesta-Asset behavior | Why |
| --- | --- | --- |
| Link = relationship between two objects | Relation can carry attributes (`owns effective-date`, `owns committed-amount`) and N roles | PE relationships are almost always dated and quantified |
| Action type = bundled edits + side effects | Command + workflow step: every action is validated, permissioned, audit-logged, and optionally approval-gated | Financial writes require provenance and T2 controls |
| Roles = resource-level grants | ABAC adds purpose, classification, and entity-path scoping | LP data and deal-room material need finer control |
| Interfaces = shape polymorphism | Abstract supertypes (`party`) give structural polymorphism; queries can match any `party` playing `ownership:owner` | Look-through needs owner/asset polymorphism |
| Functions on objects | Two layers: TypeQL functions for graph-shaped logic; versioned metric DSL for finance formulas with ledger lineage | Metrics must cite ledger entries, not just graph state |
| Object View = configured hub | Dossier tabs are built screens, not per-object configuration | Consistent UX; customization via filters/panels |

## Correspondence to the dataset model

The reference frames ontology as analogous to datasets — dataset : object type, row : object, column : property, join : link. Mesta-Asset keeps this framing for ingestion:

- Each vendor dataset maps to entity types and attribute types in the Ontology.
- Source rows become instances; joins become typed relations with roles.
- The mapping rule itself is versioned in the adapter — when a vendor schema changes, the adapter changes, the Ontology does not.

## Governing rules

- The Ontology is the canonical vocabulary: UI labels, API fields, metric names, and report sections resolve to Ontology types.
- Adding a new entity/relation/attribute type is an Ontology change — T2, SemVer, deprecation before deletion.
- Action-type equivalents (commands) declare: required role, allowed states, side effects, audit payload, and approval requirements.
- Functions and metric definitions are versioned alongside the schema; an IC report cites the exact versions used.
