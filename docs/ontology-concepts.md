# Ontology Concepts — Reference Mapping

How the canonical ontology-platform concepts map onto OCTO's implementation. The reference model describes a digital-twin Ontology: object types, properties, link types, action types, roles, functions, interfaces, and object views. OCTO implements the same concepts on vendor-neutral technology: Neo4j/Cypher for the semantic graph (ADR-0004), PostgreSQL for the ledger, OWL/SHACL for formal validation, and Kotlin services for behavior.

## Concept map

| Reference concept | OCTO equivalent | Where it lives |
| --- | --- | --- |
| Ontology (digital twin) | Investment Ontology — canonical model of prospects, funds, companies, LPs, GPs, documents, and events | `ontology/octo-investment.cypher` + OWL/SHACL in Git |
| Object type | `// type: entity` → node label, kebab name rendered PascalCase (e.g. `operating-company` → `:OperatingCompany`) | `ontology/` schema |
| Object | Entity instance | Neo4j node |
| Object set | Query result set / typed collection | Cypher `MATCH` results |
| Property | `// type: attribute` + `// owns:` declaration (e.g. `owns: committed-amount` → property `committedAmount`) | `ontology/` schema |
| Property value | Property value on a node or relationship | Neo4j data |
| Shared property | Property key reused across labels (e.g. `externalId`, `effectiveDate`, `currencyCode`) | `ontology/` schema — same `// type: attribute` name reused |
| Link type | `// type: relation` — a relationship type for 2 roles (`:COMMITMENT`, `:OWNERSHIP`), a reified node for 3+ (`:FundInvestment`) | `ontology/` schema |
| Link | Relationship instance `(:LimitedPartner)-[:COMMITMENT]->(:Fund)` or the reified node | Neo4j relationship/node |
| Action type | Application command — a typed, permission-checked, audited mutation delivered through `api` + `workflow` (e.g. `advanceDealStage`, `recordValuation`, `approveReport`) | `modules/api`, `modules/workflow` |
| Roles (permissioning) | RBAC + ABAC scoped by organization/fund/deal/entity/document/field/action | `modules/api` policy layer |
| Functions | Cypher queries for graph computation; Kotlin domain services and the metrics DSL for financial math | `ontology/` + `modules/analytics` |
| Interfaces (polymorphism) | Label chains — `:Party` (abstract) → `:Organization` → `:FundManager`/`:LimitedPartner`/`:OperatingCompany`; a subtype node carries every ancestor label, inherited properties and role-playing | `ontology/` schema |
| Object View | Entity dossier screens — Company Details, Fund Metrics, prospect detail — the 360° hub for one entity | `user-interfaces.md` |
| Dataset → object type analogy | Source adapter mapping: each vendor dataset maps to entity types + attributes | `modules/ingestion` mappings |
| Join → link type analogy | Relationships replace foreign-key joins; links are first-class and traversable | Neo4j |

## Semantic differences that matter

| Reference behavior | OCTO behavior | Why |
| --- | --- | --- |
| Link = relationship between two objects | Relationships carry properties (`effectiveDate`, `committedAmount`); a relation with more than two roles reifies as a node with one edge per role | PE relationships are almost always dated and quantified |
| Action type = bundled edits + side effects | Command + workflow step: every action is validated, permissioned, audit-logged, and optionally approval-gated | Financial writes require provenance and T2 controls |
| Roles = resource-level grants | ABAC adds purpose, classification, and entity-path scoping | LP data and deal-room material need finer control |
| Interfaces = shape polymorphism | Abstract labels (`:Party`) give structural polymorphism; `MATCH (:Party)-[:OWNERSHIP]->()` matches any party on the owner side | Look-through needs owner/asset polymorphism |
| Functions on objects | Two layers: Cypher queries for graph-shaped logic; versioned metric DSL for finance formulas with ledger lineage | Metrics must cite ledger entries, not just graph state |
| Object View = configured hub | Dossier tabs are built screens, not per-object configuration | Consistent UX; customization via filters/panels |

## Correspondence to the dataset model

The reference frames ontology as analogous to datasets — dataset : object type, row : object, column : property, join : link. OCTO keeps this framing for ingestion:

- Each vendor dataset maps to entity types and their properties in the Ontology.
- Source rows become nodes; joins become typed relationships (reified nodes when the relation has more than two roles).
- The mapping rule itself is versioned in the adapter — when a vendor schema changes, the adapter changes, the Ontology does not.

## Governing rules

- The Ontology is the canonical vocabulary: UI labels, API fields, metric names, and report sections resolve to Ontology types.
- Adding a new entity/relation/attribute type is an Ontology change — T2, SemVer, deprecation before deletion.
- Action-type equivalents (commands) declare: required role, allowed states, side effects, audit payload, and approval requirements.
- Functions and metric definitions are versioned alongside the schema; an IC report cites the exact versions used.
