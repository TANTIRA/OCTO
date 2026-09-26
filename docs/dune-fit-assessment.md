# Dune — fit assessment

Evaluation of [Dune](https://dune.com) — the blockchain data analytics platform queried through DuneSQL and the query-execution API — as an enrichment and analytics source for Mesta-Asset. No API calls were made; this is a documentation-only assessment of the published CLI reference and API surface.

- **Risk tier:** T0 for this document. T1 for a read-only enrichment/analytics adapter whose output never touches the ledger. T2 if query output ever feeds screening decisions, claim evidence, or a recon break.
- **Status:** proposed. Dune is a candidate context provider, not a ledger-fact source. Unrelated to the instrument-ontology decision (#109) because nothing it returns may enter `instrument_flow` or `ledger_event`.

## What Dune is

A managed analytics warehouse over indexed chain data: canonical tables (blocks, transactions, traces, logs), ABI-decoded contract tables, Spellbook transformations (curated higher-level tables like `dex.trades`), and community datasets — queried in DuneSQL (Trino) across 100+ chains, with saved queries, parameters, visualizations, dashboards, and metered credits.

| Surface | Interface | In scope? |
| --- | --- | --- |
| Query execution API (submit → poll `QUERY_STATE_*` → results) | `dune query run`, `run-sql`, `execution results` | Yes — production integration surface for scheduled enrichment queries |
| Saved queries with parameters | `dune query create/update/run --param` | Yes — versioned, reusable queries; parameters keep wallet/time inputs out of query text |
| Dataset catalog (canonical / decoded / spell / community) | `dune dataset search`, `search-by-contract` | Yes — discover which tables exist before writing queries |
| Documentation search | `dune docs search` | Yes — free, unauthenticated; DuneSQL syntax lookup |
| Credit usage monitoring | `dune usage` | Yes — required for budget control |
| Visualizations and dashboards | `dune viz *`, `dune dashboard *` | Dev/ops tooling — operator-facing analytics, not a platform data path |
| The `dune` CLI itself | local binary | Dev/prototyping tool only — production calls go through the REST execution API, never a shell-out |

## The line that decides everything

Dune returns **computed datasets**, not chain facts. Its tables are Dune's own indexing plus community-maintained transformations, with indexing lag and no per-row commitment level. That places it on the far side of the staging boundary:

| Layer | Source | Can write financial facts? |
| --- | --- | --- |
| Staging / `instrument_flow` / `ledger_event` | Helius RPC at `finalized` | Yes — T2, gated on commitment |
| Analytics context, screening signals, recon cross-check | Dune query results | **No — evidence and context only** |

Consequences:

- **A Dune row is never promoted into the ledger.** If a Dune figure is needed as a fact, it must be re-derived from raw chain reads (Helius) or corroborated administrator data. Dune can point at a discrepancy; it cannot prove one.
- **No finality gate exists on the Dune side.** The `finalized`-only rule that protects staging has no analogue here — there is nothing to gate because nothing is written.
- **Execution is batch, not stream.** States run `PENDING → EXECUTING → COMPLETED` in seconds-to-minutes. Suitable for daily/weekly enrichment; unusable for live ingestion, which stays with webhooks + poller.

## Where it could serve the business

Uses are listed in rising order of risk:

| Use | What the platform gains | Tier |
| --- | --- | --- |
| Market and protocol context for analysts (TVL trends, holder concentration, volume) | `analytics` — sector metrics without building per-protocol adapters | T1 |
| Multi-chain context (Ethereum, Base, Arbitrum) before those adapters exist | Chain coverage via SQL instead of three more vendor integrations | T1 |
| Second source for onchain claims in screening | `deal-sourcing` — corroborates `ClaimVerifier` evidence; an independent pipeline catches provider drift | T2 — feeds a decision |
| Aggregate-level recon cross-check ("company claims X protocol revenue; spell says Y") | `recon` — a discrepancy *signal* a reviewer resolves against chain facts | T2 |
| Operator dashboards for platform health and credit spend | Internal observability | T0/T1 |

## Detection rules an adapter inherits

- **Spells and community tables are crowd-maintained code.** A spell's semantics can change under a table name. Pin the exact dataset in fixtures, version the saved queries in Git, and re-verify semantics on any pipeline change — the same response-shape discipline as Helius.
- **Freshness lag is real.** Dune indexing trails finalized chain state. Never answer "what does this wallet hold now" from Dune — that is the Helius Wallet API's job.
- **Parameters, never string interpolation.** `--param key=value` exists so wallet addresses and dates are typed inputs, not concatenated SQL. Any adapter must do the same; a query string built from external input is an injection path.
- **Credits are metered.** Poll `dune usage`; scheduled enrichment needs a per-environment credit budget, same as the Wallet API's 100-credits-per-call budgeting.
- **`DUNE_API_KEY` is a Confidential credential**: environment variable only, never logged, never passed as `--api-key` where terminal history is visible, never in prompts.

## Where it does not fit

| Excluded | Reason |
| --- | --- |
| Staging, `instrument_flow`, `ledger_event`, any ledger fact | Dune is derived data — no commitment level, no provenance the IBOR can defend |
| Live ingestion for watched addresses | Batch execution model; webhooks + poller own the hot path |
| Portfolio valuation inputs | Same methodology gap as Helius `usd_value` — an aggregate is context, not a NAV input |
| Real-time or alerting paths | `QUERY_STATE_PENDING` latency is incompatible with alert SLAs |
| Shelling out to the `dune` CLI from the platform | The CLI is an operator tool; the adapter calls the REST execution API |
| Unreviewed use as an agent tool | If a `dune` MCP or agent tool is proposed, the third-party MCP rule applies — Security Blue Team review first |

## Risks

- **Provider correctness, squared:** spells layer community transformations on Dune's indexing — two trust steps removed from consensus data. Mitigation: treat every output as a signal requiring chain-level corroboration before it influences a decision.
- **Semantic drift:** spell definitions change silently. Pin dataset versions, keep query text in Git, alert on schema changes.
- **Credit exhaustion:** scheduled queries burn credits; a broken polling cadence can drain the plan. `dune usage` monitoring plus per-query budgets.
- **False authority:** the biggest risk is cultural, not technical — a clean dashboard number reads as a fact. Every Dune-sourced field must be labeled `source_system`-style so a reviewer sees *derived*, not *observed*.

## Architectural placement

Same vendor rule as Helius: formats stop at the adapter, and the adapter sits in a different layer entirely.

```text
Dune query-execution API ──► analytics/enrichment adapter ──► screening signals, recon context
        │                             │
 vendor shapes stop here        never crosses into staging / instrument_flow / ledger_event
 (analytics/dune/* or ingestion/enrichment/dune/*)
```

The boundary that matters is not vendor-vs-neutral (ADR-0001 covers that) — it is **derived-vs-observed**. Helius rows carry `finalized`; Dune rows carry a query id. Only the first may become a fact.

## Before adopting

- [ ] Product: name the first use — screening signals, recon cross-check, or analyst context
- [ ] Security: `DUNE_API_KEY` provisioning; confirm no Confidential data is sent in query text
- [ ] Ops: credit budget per environment; `dune usage` alerting
- [ ] Governance: decision record on which spell/community tables are approved as evidence-grade, with semantics reviewed per table
- [ ] If outputs feed screening: raise to T2 — Tech Lead + security review, threat model

## Open questions

- Does recon want Dune as a *scheduled* cross-check or an *on-demand* tool a reviewer invokes during break resolution?
- Community spells vs building equivalent queries on canonical tables — the trade is curation vs verifiable semantics.
- If EVM chains enter scope, does Dune context suffice for early coverage, or does the ledger-grade requirement force the Helius-style adapter anyway? (Likely the latter — context never substitutes for facts.)
