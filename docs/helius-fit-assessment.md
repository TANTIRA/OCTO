# Helius — fit assessment

Evaluation of [Helius](https://www.helius.dev/llms-full.txt) as the Solana onchain data source for Mesta-Asset. No API calls were made; this is a documentation-only assessment of the published docs and API catalog.

- **Risk tier:** T0 for this document. T2 for a read-only ingestion adapter, because it writes financial facts to the ledger. T3 for anything that signs or submits transactions.
- **Status:** proposed. Solana is the first chain in scope; Helius is the designated provider. Blocked on the same decision as [xStocks](xstocks-fit-assessment.md) and [Arbitrum](arbitrum-fit-assessment.md): the ontology has no instrument concept (tracked in #109).

## What Helius is

Solana's infrastructure provider and developer platform: high-performance RPC nodes, enhanced transaction APIs, a Digital Asset Standard (DAS) API, wallet-level portfolio endpoints, webhooks, gRPC streaming (LaserStream), transaction submission (Sender), priority-fee estimates, staking endpoints, and ZK-compression indexing.

| Surface | Endpoint | In scope? |
| --- | --- | --- |
| Solana RPC (HTTP) + extensions (`getTransactionsForAddress`, `getTransfersForAddress`) | `mainnet.helius-rpc.com` | Yes — history backfill, balances |
| Wallet API (balances, holdings, transfer history, funded-by, identity) | `api.helius.xyz/v1/wallet` | Yes — holdings snapshots for recon |
| DAS API (assets, NFTs, compressed, proofs) | `mainnet.helius-rpc.com` | Yes — asset-level holdings where Wallet API is insufficient |
| Webhooks (enhanced transaction / account events) | `api.helius.xyz/v0/webhooks` | Yes — live ingestion for watched addresses |
| Staking (stake accounts, rewards) | `mainnet.helius-rpc.com` | Yes — staking-reward flows |
| Priority Fee API | `mainnet.helius-rpc.com` | No — transaction submission concern |
| Sender | `sender.helius-rpc.com` | No — T3, the platform never signs |
| LaserStream (gRPC) | `laserstream-*` | Not v1 — webhooks + poller suffice |
| ZK Compression indexer | `mainnet.helius-rpc.com` | No — no compressed-state requirement |
| Admin API (usage, billing) | `admin-api.helius.xyz` | No — not platform data |
| `helius-mcp` (MCP server) | `npx helius-mcp` | Deferred — candidate allowlisted tool for claim verification; needs Security Blue Team review first (third-party MCP rule) |

## Where it could serve the business

Uses are listed in rising order of risk:

| Use | What the platform gains | Tier |
| --- | --- | --- |
| Read wallet/token holdings of a fund's treasury or custody addresses | A public, independently verifiable source for `recon` | T2 adapter |
| Record token transfers, staking rewards, unlocks and airdrops as ledger facts | Dated, final onchain cash flows independent of administrator reporting | T2 adapter |
| Verify onchain claims in deal screening (holders, treasury, activity) | `extraction-source` evidence that is a ledger fact, not a document citation | T2 adapter or allowlisted tool |
| Stream live events for watched addresses | Webhook-driven ingestion without polling lag | T2 (auth boundary) |
| Submit transactions, estimate fees, operate keys | — | T3, out of scope |

The platform reads and never signs. Every in-scope row ingests facts that a custodian, program, or counterparty created.

## The finality model, mapped to the ledger

Solana commitment levels decide what may become a ledger fact:

| Level | Can it roll back? | Latency | Ledger use |
| --- | --- | --- | --- |
| `processed` | Yes — latest unconfirmed block | ~0.4 s | Never read |
| `confirmed` | Yes — voted, not rooted | ~1 s | Never read |
| `finalized` | No — rooted (~32 slots) | ~13 s | **The only level at which an event may enter staging and the ledger** |

Why `finalized` and nothing earlier:

- **The ledger is append-only.** A fact written at `confirmed` and then rolled back would need a superseding row, with a rationale, for an event that never happened. Gating every request on `commitment: "finalized"` makes that impossible by construction — no reorg-handling code is required because nothing unfinalized is ever written.
- **Staging rows record the commitment observed** (`commitment` column) so provenance survives the write.

Each onchain event maps onto the staging columns:

| Column | Source |
| --- | --- |
| `occurred_at` | `blockTime` (unix seconds) of the containing block; see [Time](#time) |
| `recorded_at` | When the adapter wrote the row after observing `finalized` |
| `external_id` | `<chain>:<signature>:<account>:<instruction-path>` — e.g. `solana:5x…:7y…:i3.2`; the unique `(source_system, external_id)` key makes poller re-scans and webhook/poller double-delivery idempotent |
| `source_system` | `helius-solana` — one value per provider-chain pair |

## Detection rules an adapter inherits

Solana's semantics impose the same class of rules as Arbitrum's exchange checklist: never credit a false movement, never miss a real one.

- Skip every transaction whose `meta.err` is non-null — failed transactions change no balances.
- Flatten `innerInstructions` with an index path (`i3.2`) so a transfer inside a CPI gets its own stable `external_id` part.
- SPL token movements are token-account balance deltas (`preTokenBalances`/`postTokenBalances` keyed by `mint`); native SOL movements are `preBalances`/`postBalances` deltas. The two must not be conflated — SOL has no mint.
- A `mint` the platform does not know is spoofable by anyone. Unknown mints are recorded with `transfer_kind='other'` and are never auto-mapped to a registered instrument.
- The Wallet API's `usd_value` fields are **valuations, not facts**. They stay in snapshot staging for recon context and never enter `instrument_flow`.

## Instruments: SPL mints and no currency code

Solana's token identity is the mint address (base58, 32–44 chars); native SOL has no mint (the `So111…112` address denotes *wrapped* SOL, a different instrument). Consequences for the IBOR:

- **Key instruments by `(chain, mint_address)`, never by symbol.** Symbols are metadata and collide freely.
- **No mint has an ISO 4217 code.** `ledger_event.currency_code` is checked against `^[A-Z]{3}$`; recording a token flow as `USD` asserts a denomination the ledger cannot observe. Token flows therefore go to a separate append-only `instrument_flow` table keyed by instrument and wallet (decided in the megaplan); fiat flows stay in `ledger_event`.
- **Store raw units plus `decimals` observed at ingestion.** The xStocks multiplier lesson applies: the convention must be persisted so a later recomputation reproduces the number.

## Time

- `blockTime` is unix seconds voted on by the cluster; it can drift a few seconds from wall time and is occasionally null on old blocks.
- The ledger dates flows by day (methodology §10.1), so drift matters only at a cut-off: a transfer near midnight on 31 December can fall on either side of year-end. **Decision needed:** which timestamp and which zone define the quarter-end cut-off for onchain flows — same open question as Arbitrum.

## The hosted-dependency tension

Helius is a hosted service behind an `api-key` query parameter with per-plan credits and rate limits:

- **429 means back off.** Honor `Retry-After`; retry `429`/`5xx` with exponential backoff (≤3 attempts); never retry `400`/`401`/`404` — those are permanent request errors.
- **`HELIUS_API_KEY` is a Confidential credential**: environment variable only, never logged, never in prompts. Basic RPC calls (`getBalance`, `getSignaturesForAddress`, `getTransaction`) could fall back to a self-hosted node; the enhanced APIs (Wallet API, DAS, webhooks, `getTransactionsForAddress`) are Helius-only and are the actual reason for adopting it.
- **Single-vendor risk is accepted and confined**: `source_system='helius-solana'` names the pair; swapping or adding a provider is a new adapter package, never a core change (ADR-0001).

## Where it does not fit

| Excluded | Reason |
| --- | --- |
| Sender, Priority Fee API, `send *` CLI commands | Transaction submission is signing-adjacent — T3 in `AGENTS.md`; agents never hold a deployer wallet or key |
| Running a Solana RPC node or validator | Infrastructure below the platform; ADR-0001 scopes Mesta-Asset above the administrator and custodian layer |
| Trade execution, swaps, DEX routing | Out of scope per ADR-0001 |
| Valuing locked or illiquid token positions | A spot price is not a valuation input for a restricted token — methodology gap, separate workstream |
| Sanctions/AML screening by the provider | KYC/AML stays with the counterparty |
| `helius-mcp` without review | Third-party MCP servers need Security Blue Team review before any agent uses them |

## Risks

- **Provider correctness:** enhanced APIs are Helius's indexing, not consensus data. Recon against raw RPC (`getTokenAccountsByOwner`) is the cross-check.
- **Credits/rate limits:** polling must budget credits; webhooks offload the hot path.
- **Spam transfers:** watched treasury addresses will receive dust/scam tokens — the unknown-mint rule keeps them out of positions.
- **Governance:** Helius API versions and pricing are outside the platform's control; pin documented request shapes in fixtures so a response-schema drift fails loudly in tests.

## Architectural placement

Helius gets the same shape as every vendor: its formats stop at the adapter.

```text
Helius RPC / Wallet API / DAS / Webhooks ──► ingestion adapter ──► staging ──► instrument_flow (finalized only)
              │                                   │
   vendor formats stop here (onchain/helius/*)    └──► recon: onchain snapshot vs derived position
```

ADR-0001's vendor-neutrality consequence applies. No module outside the adapter may depend on Helius endpoints, response shapes, or `api-key` plumbing.

## Before adopting

- [x] Product: Solana is the first chain; Helius is the designated provider
- [ ] Ontology: add the instrument/wallet/instrument-flow vocabulary (#109) — T2, CTO-owned
- [ ] Ledger: `instrument_flow` table decided — token flows do not enter `ledger_event` (#110)
- [ ] Ops: set the quarter-end cut-off rule for `blockTime`
- [ ] Security: `HELIUS_API_KEY`/`HELIUS_WEBHOOK_SECRET` provisioning and the webhook shared-secret auth model (#114)
- [ ] Blue Team: review `helius-mcp` before any agent-facing use (#116)

## Open questions

- Does the custodian or administrator already report these flows? If so, the chain is a reconciliation source rather than a primary one.
- Which arrives first for the target firm: treasury/token transfers, staking rewards, or claim verification in screening?
- When EVM enters scope, does the `(chain, mint/contract)` key generalize cleanly, or does `solana-address` need a sibling type?
