# Arbitrum — fit assessment

Evaluation of [Arbitrum](https://docs.arbitrum.io/llms-full.txt) as a settlement network and onchain data source for Mesta-Asset. No RPC calls were made; this is a documentation-only assessment of the published docs.

- **Risk tier:** T0 for this document. T2 for a read-only ingestion adapter, because it writes financial facts to the ledger. T3 for anything that holds keys, signs, bridges, or deploys contracts
- **Status:** superseded into a design. The instrument blocker is resolved (`instrument`/`instrument_flow` exist since V10) and the adapter design lives in [arbitrum-ingestion-design.md](arbitrum-ingestion-design.md); the `ledger_event` mapping below predates that table

## What Arbitrum is

A family of Ethereum rollups built on the Nitro stack. Four stages matter here:

1. **Sequencer.** Orders transactions and publishes soft confirmations on its feed.
2. **ArbOS.** Executes the transactions.
3. **Batch poster.** Compresses the transactions and posts batches to Ethereum.
4. **BoLD.** Settlement against Ethereum completes when this rollup protocol confirms an assertion.

| Network | Chain ID | Data availability | Notes |
| --- | --- | --- | --- |
| Arbitrum One | 42161 | Rollup: all batch data on Ethereum | The network to assess first |
| Arbitrum Nova | 42170 | AnyTrust: a Data Availability Committee holds the data; Ethereum gets a certificate | A weaker data-availability assumption than One |
| Arbitrum Sepolia | 421614 | Rollup on Sepolia | Testnet. Receives ArbOS upgrades before One |

## Where it could serve the business

Uses are listed in rising order of risk:

| Use | What the platform gains | Tier |
| --- | --- | --- |
| Read tokenized holdings and stablecoin balances that a fund, LP, or portfolio company holds onchain | A public, independently verifiable source for `recon` (WF-3 in [user-workflows.md](user-workflows.md)) | T2 adapter |
| Record capital calls and distributions settled in USDC | Dated cash flows that are final about 15 minutes after settlement, independent of the administrator's reporting cycle | T2 adapter. The payment itself stays with the fund's custodian |
| Issue or transfer tokenized fund interests | — | T3, out of scope |

The platform reads and never signs. Every row above ingests facts that a custodian, administrator, or counterparty created.

## The finality model, mapped to the ledger

An Arbitrum block is only as final as the Ethereum block that carries its batch.

| Level | JSON-RPC block tag | Can it revert? | Latency on Arbitrum One | Ledger use |
| --- | --- | --- | --- | --- |
| Soft confirmation | `latest` | Yes: a promise from the Sequencer | Sub-second | Display only, labeled unconfirmed |
| Parent-chain safe | `safe` | Only in a deep Ethereum reorg | Minutes | Staging at most |
| Parent-chain final | `finalized` | No | About 12–15 minutes | **The only level at which an event becomes a `ledger_event`** |
| Assertion confirmed | none | No | The dispute window, about 6.4 days | Needed before a withdrawal to Ethereum can be claimed |

The Fast Feed on Arbitrum One is not a finality level. Its block numbers are tentative, it carries no block hash, and inclusion is not guaranteed.

Why `finalized` and nothing earlier:

- **The ledger is append-only.** `mesta.ledger_event` never updates or deletes a row. A fact written before finality and then reorged away would need a superseding row, with a rationale, for an event that never happened. Finalized blocks do not reorganize, so gating on `finalized` makes that impossible.
- **Wait on the block tag, not a confirmation count.** Ethereum's "12 blocks" habit does not translate: Arbitrum produces blocks far faster than Ethereum finalizes them.
- **Below `finalized`, detect reorgs by block-hash continuity** (`parentHash`) and rewind to the common ancestor. Never key anything on a block number alone: after a reorg, the same number can hold different transactions.

Each onchain event maps onto the existing columns:

| `ledger_event` column | Source |
| --- | --- |
| `occurred_at` | The Arbitrum block timestamp; see [Time](#time) |
| `recorded_at` | When the adapter saw the block reach `finalized` |
| `external_id` | Chain ID, transaction hash, and log index, or trace position for value moved by an internal call. The existing unique key on `(source_system, external_id)` then makes a re-scan idempotent |
| `source_system` | The adapter, one value per chain |

## Time

- **Timestamps follow the Sequencer's clock.** They never go backwards. They must fall between 24 hours before and 1 hour after the current time, which lets the Sequencer lag when batch posting stalls.
- **Force-included transactions are dated on entry.** A transaction force-included from Ethereum carries the time it entered the delayed inbox, or the previous block's timestamp if that is later.
- **Store the Arbitrum block number and hash.** `block.number` inside a contract returns an approximate Ethereum block number. RPC receipts return the Arbitrum block number, plus `l1BlockNumber`.

The docs call these timestamps reliable over hours, not minutes. The ledger dates flows by day (methodology §10.1), so this matters only at a cut-off: a distribution near midnight on 31 December can fall on either side of year-end. **Decision needed:** which timestamp and which zone define the quarter-end cut-off for onchain flows.

## Detection rules an adapter inherits

Arbitrum's exchange integration checklist solves the ingestion layer's problem: never credit a false movement, and never miss a real one.

- Scan every transaction in every block, in order, with no gaps.
- Credit nothing whose receipt status is not `0x1`.
- Never trust calldata alone.
  - Confirm token movements against `Transfer` logs emitted by an allowlisted contract address.
  - Confirm ETH moved by internal calls against `callTracer` traces. Traces need `debug_traceBlockByHash`, so the node or provider must expose the debug API.
- Arbitrum-specific transaction types matter:
  - Bridge deposits (`0x64`) and retryable tickets (`0x68`, `0x69`) can deliver value.
  - The ArbOS internal transaction (`0x6A`) never does.
- A `Transfer` event from an unknown contract is spoofable. Only allowlisted contract addresses count.
- The checklist's eight test vectors (TV-1 to TV-8) become the adapter's contract tests:
  - capture them on Arbitrum Sepolia;
  - pin them to the Nitro and ArbOS versions;
  - replay them before every ArbOS upgrade reaches Arbitrum One. ArbOS upgrades are hard forks and can change trace output, gas accounting, and transaction types.

## Instruments: two USDCs and no currency code

| | Native USDC | Bridged USDC.e |
| --- | --- | --- |
| Contract on Arbitrum One | `0xaf88d065e77c8cC2239327C5EDb3A432268e5831` | `0xff970a61a04b1ca14834a43f5de4533ebddb5cc8` |
| Origin | Issued natively; moves between chains through Circle's Cross-Chain Transfer Protocol | Ethereum USDC locked in the canonical bridge and minted on Arbitrum |
| Redemption | Directly redeemable 1:1 for US dollars | Bridged back to Ethereum USDC |

This has two consequences for the IBOR:

- **They are different instruments.** Key them by chain ID and contract address, never by symbol, and never net one against the other.
- **Neither has a currency code the ledger accepts.** `ledger_event.currency_code` is checked against `^[A-Z]{3}$`, and `USDC` is not an ISO 4217 currency. Recording a stablecoin flow as `USD` asserts a peg the ledger cannot observe. Recording it as a token needs the instrument concept the ontology lacks. **Decision needed** before any stablecoin flow enters the ledger.

## Cash in transit

A withdrawal from Arbitrum One to Ethereum through the canonical bridge can be claimed only after its assertion is confirmed. The dispute window is 45,818 Ethereum blocks, about 6.4 days, plus some padding. For that week, the value is on neither chain.

- `recon` would report a discrepancy for the whole window unless "in transit" is modeled as a state.
- Liquidity coverage (methodology §7.3) must not count in-transit value as a liquid asset.
- Do not hard-code the duration. The docs send integrators to the bridge documentation for the current mechanics.

Third-party fast bridges avoid the wait, but the docs note that third parties run them. That is counterparty exposure, not faster settlement.

## Where it does not fit

| Excluded | Reason |
| --- | --- |
| Keys, wallets, signing, bridging funds, deploying Solidity or Stylus contracts | T3 in `AGENTS.md`. Agents never hold a deployer key or wallet |
| Running a node, validator, batch poster, or Arbitrum chain | Infrastructure below the platform. ADR-0001 scopes Mesta-Asset above the administrator and custodian layer |
| Trade execution, Timeboost, priority gas auctions | Trade execution is out of scope per ADR-0001 |
| Valuing private portfolio companies | An oracle price feed is not a valuation input for an unlisted company |
| Sanctions screening by the chain | Compliance filtering (ArbOS 61) is an optional chain-owner feature, off by default and not intended for Arbitrum One. KYC and AML stay with the counterparty |
| The public RPC endpoint in production | The docs give it no uptime, latency, or rate-limit guarantees. Production needs a dedicated node or a provider, which is a vendor decision |

## Risks the docs name

- **Software:** a non-zero chance of undiscovered vulnerabilities that put funds at risk. Audits and a bug bounty mitigate it.
- **Governance:** the Arbitrum DAO owns Arbitrum One and Nova. Upgrades are its decisions, outside the platform's control.
- **Sequencer:** soft confirmations rely on the Sequencer's honesty and uptime. It can delay a transaction for about 24 hours before force inclusion applies, but it cannot censor it permanently.
- **Nova:** AnyTrust replaces full onchain data availability with a committee.

## Architectural placement

Arbitrum gets the same shape as every vendor: its formats stop at the adapter.

```text
Arbitrum node / provider ──► ingestion adapter ──► staging ──► ledger (finalized only)
  (JSON-RPC, debug API)             ▲                 │
                         chain formats stop here      └──► recon: onchain vs IBOR
```

ADR-0001's vendor-neutrality consequence applies. No module outside the adapter may depend on chain IDs, transaction types, or ABI encodings.

## Before adopting

- [ ] Product: decide whether there is a requirement for onchain holdings, stablecoin cash flows, or neither. Until there is, this is out of scope
- [ ] Ontology: add the instrument concept from the xStocks assessment, with tokens identified by chain and contract address. T2, CTO-owned
- [ ] Ledger: decide how a stablecoin flow is recorded (USD cash under a peg assumption, or a token position) before any enters `ledger_event`
- [ ] Ops: set the quarter-end cut-off rule for onchain timestamps
- [ ] Recon: model an in-transit state for bridge withdrawals
- [ ] Security: select the node or provider, and review the T3 boundary before anything touches keys

## Open questions

- Which comes first for the funds and LPs on the platform: stablecoin capital calls, tokenized fund interests, or tokenized treasury holdings?
- Do the fund's custodian or administrator already report these flows? If so, the chain is a reconciliation source rather than a primary one.
- Is Arbitrum One the only network in scope, or do Nova and the other chains that carry the same tokens need the same adapter?
