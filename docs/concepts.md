# Concept inventory

Canonical register of Octo's product concepts — the units of functionality users and
developers must understand, independent of how they are implemented. Modeled on the
concept-centric development method (Wilczynski, Gregoire-Wright, Jackson,
arXiv:2304.14975): one concept, one purpose; canonical names everywhere; aliases map
familiar or legacy terms during vocabulary transitions.

Status values: `ratified` (agreed, not yet built) · `built` (shipped in code) ·
`deprecated` (marked for removal — deprecate before deleting, same policy as
`owl:deprecated`).

Edits to this file go through PR review. Issues and PRs should reference canonical
concept names. A concept that grows a second purpose is split, not extended.

## Ledger & position

| Concept | Purpose | Where | Status | Aliases |
| --- | --- | --- | --- | --- |
| Ledger event | Append-only record of an ISO-currency investor flow; the IBOR fact | `octo.ledger_event`, `ibor-core` | built | ledger entry, transaction |
| Instrument flow | Append-only record of a non-ISO flow (token, NFT, stake account); ledger event's onchain counterpart | `octo.instrument_flow`, `ibor-core` | built | token transfer, onchain flow |
| Valuation event | Point-in-time valuation record feeding analytics | `octo.valuation_event` | built | mark, valuation |
| Position | Current holding, always derived from ledger + instrument flows — never written | `ibor-core` (derivation) | built | holding |
| Capital event | PE corporate action: capital call, distribution, fee, carried interest | `ibor-core` `FlowType` + `analytics/Pacing.kt` primitives | built | drawdown, call |

## Ingestion & staging

| Concept | Purpose | Where | Status | Aliases |
| --- | --- | --- | --- | --- |
| Staging | Landing zone for untrusted vendor data; nothing here is a ledger fact | `onchain_transfer`, `onchain_balance_snapshot`, `document_classification`/`claim_assessment` (V2 decision staging), `timeseries_observation` | built | raw intake, landing |
| Promotion | Verified transition from staging to ledger facts; dedupe via `source_system`+`external_id`, correction via `supersedes_id`+rationale | `InstrumentFlowPromoter`, `ibor-core` | built | finalize, commit |
| Finality gate | Only chain state observed as `finalized` may promote; vendor commitment claims are never trusted | `OnchainWebhookService`, `FinalityProbe` | built | commitment check |
| Tracked address | Wallet under observation by the onchain ingestion | `octo.tracked_address` | built | watched wallet |
| Cursor | Per-address resume point for the ingestion poller — derived from the staged rows themselves (`newestStagedSlot`), not stored separately | adapter stores (`JdbcOnchainStagingStore`) | built | checkpoint, bookmark |
| Time series | Bi-temporal fact: `effective_date` + `recorded_at`, append-only | `octo.dataset` + `octo.timeseries_observation` | built | point-in-time data |

## Investment ontology

| Concept | Purpose | Where | Status | Aliases |
| --- | --- | --- | --- | --- |
| Instrument | Anything a wallet can hold that is not an ISO currency, keyed by `instrument-id` | `octo.instrument`, `ontology/` | built | token, mint, contract |
| Asset | Private-market position entity with external identifier xrefs | `octo.asset`, `asset_xref` | built | holding entity, security |
| Wallet | Custody point for instruments on a chain | ontology, `wallet-custody` | built | address, account |
| Fund / LP / GP / portfolio company / prospect | Core private-markets entities | `ontology/octo-investment.cypher` (`fund`, `limited-partner`, `fund-manager`, `operating-company`) | built (graph side); prospect also has `octo.prospect` | vehicle, investor, manager |
| Look-through | Path-sum exposure across entity/instrument hierarchies; rejects cycles | `lookthrough/Exposure.kt` | built | transparency, drill-down |

## Governance & workflow

| Concept | Purpose | Where | Status | Aliases |
| --- | --- | --- | --- | --- |
| Task | Unit of routed operational work with assignment rules | `octo.workflow_task` | built | work item, ticket |
| Approval gate | High-impact outbound artifacts require human approval before release | `workflow/report` | built | release gate, sign-off |
| Audit event | Immutable record of a governed action | `octo.audit_event` | built | audit trail |
| Tenant | Access boundary for firm-level data isolation | `octo.tenant` + `octo.tenant_member(_event)` (V8) | built | org, workspace |

## Analytics & reporting

| Concept | Purpose | Where | Status | Aliases |
| --- | --- | --- | --- | --- |
| Model run | Versioned invocation of an analytics engine | `octo.model_run`, `analytics/` | built | computation, engine run |
| Report job | Async report lifecycle: new → executing → done/error | `octo.report_job` | built | report request |
| Metric | Deterministic PE measure: DPI, RVPI, TVPI, XIRR, KS-PME, direct alpha, attribution | `analytics/` | built | KPI, measure |
| Break | Reconciliation discrepancy with a resolution lifecycle | `octo.reconciliation_break`, `recon/` | built | discrepancy, exception |
| Claim | Evidence-linked assertion under verification | `claim_assessment`, `onchain_claim_evidence` | built | assertion, attestation |

## AI & agents

| Concept | Purpose | Where | Status | Aliases |
| --- | --- | --- | --- | --- |
| Decision model | Versioned model + endpoint for assisted decisions, with audit | `control-panel` (Jev via OpenRouter) | built | scorer, classifier |
| Screening | Configurable prospect filtering before DD work | `deal-sourcing` (#6 slice 11+) | ratified | deal filter |
| Alert rule / NL query / draft | Agents that act on governed data; eval sets + tool allowlists required | `control-panel` | ratified | agent, assistant |

## Alias register — naming debt that survives by design

| Term | Resolves to | Note |
| --- | --- | --- |
| `octo` | DB schema name | Deliberately unchanged by the Octo rebrand — schema is not code namespace (#183). Schema `octo`, package `com.octo`. |
| `octo` | Product, org, Kotlin namespace | Aliases: OCTO, octo-asset, `com.octo.asset` (all legacy) |
| staging | the Staging concept | Three table shapes, one concept — same promote-to-facts contract |
| ledger event / instrument flow | the append-only flow concept | Split by denomination (ISO vs non-ISO), not by kind — do not add a third ledger |
| asset / instrument | unresolved overlap | Asset = private-market entity + xrefs; instrument = canonical holdable key. Resolution tracked under #109/#110 follow-through — do not merge casually |
| transfer | three stages of one concept | adapter-normalized → staged row → promoted flow; name the stage when ambiguous |
