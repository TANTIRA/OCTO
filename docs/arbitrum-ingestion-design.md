# Arbitrum ingestion — adapter design

Design for a read-only Arbitrum adapter feeding the existing onchain pipeline. This is the "how" companion to [arbitrum-fit-assessment.md](arbitrum-fit-assessment.md), which answered "should we" and remains the finality and risk reference. The platform reads and never signs — nothing in this design touches keys, transactions, bridging, or contract deployment (T3 exclusions stand).

- **Risk tier:** T0 for this document. T2 for what it specifies — an ingestion adapter that writes financial facts. See `AGENTS.md` risk tiers and the fit assessment's T3 exclusions.
- **Status:** proposed design, pending an issue. Several items are marked **decision needed** — agree them in the issue before code. Execution order and per-phase done-criteria live in [arbitrum-megaplan.md](arbitrum-megaplan.md).
- **Grounded in:** [arbitrum-fit-assessment.md](arbitrum-fit-assessment.md), [helius-fit-assessment.md](helius-fit-assessment.md), [ADR-0001](adr/0001-platform-architecture.md), `V10__onchain_ingestion.sql`, `V16__onchain_claim_evidence.sql`, `ontology/mesta-investment.tql`, `modules/ingestion/.../onchain/`, [Arbitrum docs](https://docs.arbitrum.io/llms-full.txt)

## What changed since the fit assessment

The assessment predates V10. Three things it was waiting on now exist:

- The ontology has an `instrument` concept (`instrument-id` key, `chain-id`, `decimals`, `instrument-kind` @values). The blocker it shared with xStocks is resolved for the Postgres side.
- Token flows do not enter `ledger_event`; they stage in `onchain_transfer` and promote into append-only `instrument_flow` (`modules/ibor-core`). The assessment's `ledger_event` column mapping is superseded by the staging mapping below.
- Promotion (`InstrumentFlowPromoter`), reconciliation (`reconcileOnchain`), and claim evidence (`OnchainEvidence`/`ClaimVerifier`) are already vendor-neutral. None of them change for a second chain.

The Helius assessment's closing question — "does the `(chain, mint)` key generalize?" — is answered here: yes. `mint_address` carries the EVM contract address; `null` remains the chain's native asset (SOL on Solana, ETH on Arbitrum).

## Scope

| Decision | Value |
| --- | --- |
| Network | Arbitrum One (chain ID 42161). Arbitrum Sepolia (421614) provides test fixtures |
| Nova | Excluded — AnyTrust replaces onchain data availability with a committee; different trust model, revisit separately |
| Direction | Read-only. The adapter never signs, submits, or estimates for submission |
| Delivery | Poller only in v1. No webhook — see below |
| Assets | ERC-20 `Transfer` legs; native ETH per the trace decision below |

**Why poller-only.** Helius webhooks bought freshness; on Arbitrum the `finalized` block tag trails the sequencer head by ~12–15 minutes regardless of delivery mechanism, so push delivery adds a vendor-specific signature/auth surface for almost no latency gain. Polling is the honest design; a provider webhook (Alchemy Notify, QuickNode QuickAlerts) is a later adapter if ops wants it.

## Provider — decision needed

| Option | What it is | Consequence |
| --- | --- | --- |
| A. Plain JSON-RPC on a dedicated endpoint | `eth_getLogs`, `eth_call`, `eth_getBlockByNumber`, `eth_blockNumber`, `eth_getTransactionReceipt` against a provider or self-hosted Nitro | Adapter is provider-agnostic by construction — unlike Helius, where the enhanced APIs were the product. Endpoint URL + optional key is the whole vendor coupling |
| B. Provider enhanced API (e.g. Alchemy `alchemy_getAssetTransfers`) | One call returns external + internal + token transfers for an address | A real vendor shape inside the adapter and a single-vendor dependency — the same trade-off the platform already accepted for Helius |

**Recommendation: A.** Standard JSON-RPC has no vendor lock-in; the public endpoint is still unsuitable for production (no guarantees — same exclusion the fit assessment recorded), so "dedicated RPC endpoint, provider TBD" is a Security/vendor decision, not an adapter one.

### Native ETH — decision needed, downstream of the provider choice

ERC-20 legs come from `Transfer` logs; native ETH moves do not emit logs. Internal-call ETH (contracts sending ETH to a watched wallet) requires `debug_traceBlockByHash`/`callTracer` — available only if the chosen provider exposes the debug API:

- **Debug API available:** trace legs are staged with `external_id` `arbitrum-one:<txHash>:<wallet>:trace:<callPath>`.
- **Not available:** v1 stages ERC-20 legs only; ETH positions exist solely as balance snapshots (`eth_getBalance`), and recon diffs them against zero-derived ETH positions. Every ETH delta then reports as an unexplained divergence — acceptable only if no fund wallet is expected to hold ETH on Arbitrum. If they will, pick a provider with the debug API.

Bridge deposits of ETH (type `0x64`/`ArbitrumDepositTxType` transactions) also emit no `Transfer` log — the same trace dependency covers them.

## Finality mapping — unchanged posture, different mechanism

The V10 rule is "nothing unfinalized is ever staged" (`commitment = 'finalized'` CHECK, no reorg code downstream). On Arbitrum the gate is the **`finalized` block tag**: the batch carrying the transaction is posted to Ethereum and its L1 block is final (~12–15 minutes on Arbitrum One). Every read — logs, receipts, `eth_call`, balances — is issued against `finalized`, never `latest`. `safe` is never used for staging: it can still revert in a deep Ethereum reorg.

| `onchain_transfer` column | Arbitrum source |
| --- | --- |
| `external_id` | `arbitrum-one:<txHash>:<wallet>:log:<logIndex>` (or `trace:<callPath>`) |
| `chain` | `arbitrum-one` |
| `signature` | Transaction hash (`0x` + 64 hex) |
| `slot` | The child-chain block number — documented as L2 block number, not Ethereum's |
| `block_hash` | The child-chain block hash |
| `block_time` | Child-chain block timestamp (sequencer clock — the same quarter-end caveat as Solana) |
| `commitment` | `'finalized'` — the existing CHECK holds verbatim |
| `wallet` | Watched address, lowercase `0x` canonical form |
| `counterparty` | The other side of the `Transfer` (from/to). Available for free — an improvement over Solana legs, which leave it null |
| `token_account` | `null` — EVM has no token accounts |
| `mint_address` | Emitting contract address, lowercase. `null` = native ETH |
| `decimals` | `decimals()` `eth_call` resolved at ingestion — never assumed 18 |
| `direction` | `in`/`out`/`self` from topics[1]/topics[2] vs the wallet |
| `transfer_kind` | `from == 0x0` → `mint`; `to == 0x0` → `burn`; otherwise `transfer-in`/`transfer-out`. A bare `Transfer` log cannot distinguish airdrop/unlock/vesting-claim — those land as `transfer-in` or `other`, an honest degradation vs Solana's richer transaction meta |

`source_system` keeps the `<provider>-<chain>` convention: `rpc-arbitrum-one` under Option A, or `<provider>-arbitrum-one` under Option B.

## Schema changes — new migration (V17)

V10 and V16 have run; every change below is additive in a new migration. Address checks become per-chain: Solana rows keep the base58 rule, other chains get `^0x[0-9a-f]{40}$` (lowercase canonical — the adapter lowercases everything before insert).

| Table | Constraint to widen / change | New rule |
| --- | --- | --- |
| `tracked_address` | `tracked_address_solana_shape` | `(chain = 'solana' and ~ base58) or (chain <> 'solana' and ~ '^0x[0-9a-f]{40}$')` |
| `onchain_transfer` | `_wallet_shape`, `_mint_shape` | Same per-chain form; `mint_address` accepts the `0x` contract |
| `onchain_balance_snapshot` | `_wallet_shape` | Same |
| `onchain_claim_evidence` | `_address_shape` | Same |
| `instrument_flow` | `_wallet_shape` | Same |
| `instrument` | `_mint_shape` | Same; `_kind_known` gains `'erc20'`; `_native_iff_no_mint` unchanged (ETH = `native-token`, null mint) |
| `onchain_balance_snapshot` | `_source_known` | `'rpc'` already fits Option A; add `'provider-api'` only if Option B is chosen |
| `onchain_transfer` | rename `helius_payload` → `vendor_payload` | `ALTER TABLE … RENAME COLUMN` — metadata-only, append-only triggers unaffected; keeps the column honest once a second vendor writes it |

Seed row: `('arbitrum-one:native', 'arbitrum-one', null, 'native-token', 18, 'ETH')`.

`tracked_address`'s `(chain, address)` primary key already lets the same wallet be watched on both chains; the event rules trigger is chain-agnostic.

## Ontology changes — T2, CTO-owned

- `attribute evm-address, value string @regex("^0x[0-9a-f]{40}$")` — lowercase canonical, matching the DB rule.
- `entity evm-contract, sub instrument, owns evm-address @unique` — mirrors `solana-mint`.
- `instrument-kind` @values gains `"erc20"`.
- `instrument-id` convention: `arbitrum-one:native`, `arbitrum-one:contract:<lowercase address>`.
- **Decision needed — the wallet problem.** `wallet` owns `solana-address @key`: every wallet must carry a Solana address, so an Arbitrum address cannot be a `wallet`. Options:
  - (a) `entity evm-wallet, sub wallet` — inherits the `solana-address` key, which is wrong;
  - (b) parallel `entity evm-wallet` (not a subtype) owning `evm-address @key`, `chain-id`, playing `instrument-flow-of:wallet-side` and `wallet-custody:wallet-side` — additive, MINOR SemVer;
  - (c) restructure `wallet` to a generic chain-scoped address — the clean model but a MAJOR change touching existing data and SHACL shapes.
  
  **Recommendation: (b)** for this release; fold (c) into a later ontology refactor if more chains arrive.

## Adapter layout

`modules/ingestion/.../onchain/evm/` mirrors `helius/`; vendor shapes stop there (ADR-0001; `ModuleBoundaryTest` gains the EVM clause).

```text
evm/
  EvmConfig.kt              rpcBaseUrl (https enforced), chainId, poll window — key redacted from toString
  EvmRpcApi.kt              interface: logs, receipts, blocks, eth_call, eth_getBalance (finalized tag)
  EvmRpcClient.kt           HTTP JSON-RPC client + RetryPolicy (moved up to onchain/ — it is not Helius-shaped)
  EvmTransferNormalizer.kt  eth_getLogs Transfer events -> OnchainTransfer
  EvmDecimalsResolver.kt    decimals() eth_call with per-run cache; unresponsive contract -> leg skipped + reported
```

**Sync shape differs from Solana and is simpler.** The Solana poller walks each wallet's signature history. On EVM, `eth_getLogs` filters topics — two queries per block window (`topics = [Transfer, [watched…], _]` and `[Transfer, _, [watched…]]`) cover *every* watched address at once. The scan runs `lastStagedBlock + 1 → finalized head` in bounded windows (Nitro itself prefetches ~499 blocks; providers cap ranges lower). The cursor is derived from staging — max staged `slot` for the chain — never stored; the `(source_system, external_id)` key makes replays harmless either way.

**Balance snapshots:** `eth_getBalance` (native) + `balanceOf` `eth_call` per registered contract, all at `finalized` — `source='rpc'`, no Wallet-API fallback tier exists on EVM.

**Claim evidence:** generalize `OnchainEvidenceAdapter` behind a small interface, or write an `evm` sibling. Feasible on plain RPC: `treasury-balance` (`eth_getBalance`/`balanceOf`), `token-supply` (`totalSupply`), `account-activity` (`eth_getTransactionCount` + bounded log count). `holder-concentration` has **no** plain-RPC equivalent — mark it unsupported on EVM v1 (honest limit, same spirit as the Helius top-20 caveat), or note it as the first thing Option B buys.

## Detection rules the adapter inherits

The fit assessment mapped Arbitrum's exchange-integration checklist; it becomes code here:

- Scan every finalized block range; never credit a receipt whose `status` is not `0x1`.
- Only `Transfer` logs from a *contract* count — a log from an arbitrary address is spoofable noise. Unknown contracts still stage (dedupe is free) and quarantine at promotion, exactly like unknown Solana mints; expect dust/spam volume on watched treasury addresses.
- Arbitrum transaction types: `0x64` (deposit), `0x68`/`0x69` (retryables) can deliver value — visible via `Transfer` mints or trace legs; `0x6A` (ArbOS internal) never does.
- The checklist's TV-1–TV-8 vectors are the contract tests: capture on Sepolia, pin to Nitro/ArbOS versions, replay before every ArbOS upgrade reaches One (ArbOS upgrades are hard forks and can change log/trace shapes).
- No `eth_getLogs` range limit is protocol-imposed, but providers impose one — windows stay small and paginate.

## Recon and instruments — what operators must know

- **USDC vs USDC.e** on Arbitrum One are two instruments — `0xaf88d065e77c8cC2239327C5EDb3A432268e5831` and `0xff970a61a04b1ca14834a43f5de4533ebddb5cc8`. Keyed by `(chain, mint)`, never symbol; never netted. Registering them (and any watched contract) is an ops/registry task, not code.
- **In-transit bridge withdrawals:** an L2→L1 withdrawal is a truthful `transfer-out`/`burn` on `arbitrum-one` — per-chain recon stays correct. The ~6.4-day "on neither chain" gap the assessment named is a cross-chain NAV problem, out of scope for v1; record it as a follow-up, not a silent divergence.
- Recon, promotion, `ClaimVerifier`, and the `instrument_flow` derivation need no code changes.

## What does not port

Solana-only surfaces with no EVM counterpart: stake accounts / `staking-reward` flows, DAS (`'das'` snapshot source), Wallet API paging (`'wallet-api'` source), Helius webhooks. Nothing about them generalizes and nothing forces their removal.

## Risks

- **Debug-API availability gates ETH coverage** — decide the provider before implementation; a wrong pick silently shrinks scope to ERC-20.
- **ArbOS upgrades** can change transaction types and log/trace shapes; pinned fixtures + a pre-upgrade replay are the mitigation (inherited from the checklist).
- **Spam/dust** on watched addresses inflates staging volume; quarantine posture means it never reaches positions, but volume is a real cost on paid providers.
- **Sequencer timestamps** share Solana's quarter-end caveat — the same unresolved ops decision.

## Before implementing

- [ ] Issue opened; this design agreed there (T2 — plan first per `AGENTS.md`)
- [ ] Provider selected; debug-API availability confirmed (decides ETH coverage)
- [ ] Ontology: `evm-address`/`evm-contract`/wallet decision approved by CTO
- [ ] Ops: quarter-end cut-off rule for sequencer timestamps (shared with Solana)
- [ ] Ops: register the watched contract set (USDC, USDC.e, …) so promotion doesn't quarantine real flows

## Open questions

- Which arrives first on Arbitrum for a real fund: stablecoin cash flows or token positions? (Same question the assessment closed with.)
- Is `evm-wallet` a parallel entity for v1, and does `wallet` get restructured when a third chain arrives?
- Does claim evidence on EVM need `holder-concentration` enough to justify an indexer/vendor API?
- Nova and other Nitro chains: ever in scope, and does AnyTrust's committee DA meet the evidence bar?
