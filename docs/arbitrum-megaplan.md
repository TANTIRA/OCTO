# Arbitrum ingestion — megaplan

Phased execution plan for Arbitrum One ingestion into the onchain pipeline, in the same phase style as the original megaplan (`megaplan §P5`, `phase 8` in V16). Each phase is one GitHub issue and one or more `<400`-line PRs. The design rationale lives in [arbitrum-ingestion-design.md](arbitrum-ingestion-design.md); this doc is the "in what order, and when is it done" companion.

- **Risk tier:** T0 for this document. Each phase carries its own tier; the T2 phases (migration, ontology, adapter) follow the T2 rules — plan agreed in the issue before code.
- **Status:** proposed. Decisions below were agreed before this doc was written.
- **Grounded in:** [arbitrum-ingestion-design.md](arbitrum-ingestion-design.md), [arbitrum-fit-assessment.md](arbitrum-fit-assessment.md), `V10__onchain_ingestion.sql`, `V16__onchain_claim_evidence.sql`, `ontology/mesta-investment.tql`, `modules/ingestion/.../onchain/`

## Locked decisions

| Decision | Value | Consequence |
| --- | --- | --- |
| Native ETH in v1 | **ERC-20 `Transfer` legs only**; ETH exists as balance snapshots | ETH movements report as recon divergences — documented limitation. Revisit if a `debug_trace*`-capable provider is chosen (design doc §Native ETH) |
| Provider strategy | Plain JSON-RPC, `EvmConfig.rpcBaseUrl` | No vendor lock-in; `source_system` = `rpc-arbitrum-one` (or `<provider>-arbitrum-one` if a named provider is configured) |
| Finality | `finalized` block tag on every read | Same posture as Solana's `commitment='finalized'` — nothing unfinalized is staged, no reorg code |
| Ontology wallet | **Parallel `evm-wallet`** (option b in the design doc) | Additive MINOR semver change; `wallet` restructure deferred |
| Delivery | Poller only | No webhook — finality lag makes push pointless (design doc §Scope) |
| Address storage | Lowercase `0x` canonical, normalized at the adapter edge | DB and ontology rules both take `^0x[0-9a-f]{40}$` |

## Out of scope for this megaplan

Bridge in-transit modelling (cross-chain NAV follow-up), `holder-concentration` evidence (needs an indexer), Nova/AnyTrust, native ETH trace legs, any web UI surface. Each is a follow-up note in the last phase, not silent scope.

## Phases

### ARB-0 — File the issues (T0)

One issue per phase ARB-1 through ARB-9, each linking this doc and `arbitrum-ingestion-design.md`, stating its tier. Comment "in progress" before claiming a slice; check recent PRs for competing work (the repo's parallel-session rule).

**Done when:** every phase has an issue number and this doc's table is updated with them.

### ARB-1 — Migration `V17__arbitrum_evm_support.sql` (T2)

V10 and V16 are live — additive only.

- Widen the address-shape CHECKs to per-chain rules (`chain = 'solana'` keeps `^[1-9A-HJ-NP-Za-km-z]{32,44}$`; `chain ~ '^arbitrum-'` takes `^0x[0-9a-f]{40}$`):
  `tracked_address.address` (`tracked_address_solana_shape`), `onchain_transfer.wallet`/`counterparty`/`mint_address`, `onchain_balance_snapshot.wallet`, `instrument.mint_address`, `instrument_flow.wallet`, `onchain_claim_evidence.subject_address` (V16).
- `instrument_kind_known` gains `'erc20'`; `instrument_native_iff_no_mint` unchanged (ETH = `native-token`, null mint).
- Seed `arbitrum-one:native` (ETH, 18 decimals) **plus** native USDC `0xaf88d065e77c8cc2239327c5edb3a432268e5831` and bridged USDC.e `0xff970a61a04b1ca14834a43f5de4533ebddb5cc8` as distinct `erc20` rows — the classic confusion must be impossible by construction. Addresses stored lowercase.
- `ALTER TABLE mesta.onchain_transfer RENAME COLUMN helius_payload TO vendor_payload` — column is always NULL today (TODO #114 comment moves with it).
- `commitment = 'finalized'` CHECK is **not** widened — EVM staging only ever reads `finalized`-tagged blocks, so the value stays honest.

**Files:** `db/migrations/V17__arbitrum_evm_support.sql`; `modules/api/.../OnchainMigrationIT.kt` extended (EVM shape accepted on `arbitrum-one`, rejected on `solana`; base58 rejected on `arbitrum-one`; `erc20` kind; seed rows).

**Done when:** `./gradlew test --tests '*OnchainMigrationIT'` green; a Solana row still inserts.

### ARB-2 — Ontology `evm-address` / `evm-wallet` / `evm-contract` (T2, CTO-owned)

- `mesta-investment.tql`: `attribute evm-address, value string @regex("^0x[0-9a-f]{40}$")`; `entity evm-wallet` owns `evm-address @key` and plays `instrument-flow-of:wallet-side`, `wallet-custody:wallet-side` (the roles `wallet` plays); `entity evm-contract, sub instrument` owns `evm-address @unique` mirroring `solana-mint`; `instrument-kind` @values gains `"erc20"`.
- `mesta-investment-shacl.ttl`: `EvmWalletShape` (mirrors `WalletShape`), `EvmContractShape`.
- `ontology/samples/valid/` gains an EVM wallet + contract case; `invalid/` gains a malformed-address case.
- `owl:versionIRI` MINOR bump; ontology CHANGELOG; consumer-impact note in the PR.

**Done when:** ontology CI gate (RDF syntax + SHACL on samples) green; `wallet`/Solana shapes untouched.

### ARB-3 — Staging store generalization (ingestion, T2)

Provider couplings found while writing this plan:

- `JdbcOnchainStagingStore` writes `ONCHAIN_SOURCE_SYSTEM` (`'helius-solana'`) on every insert → add `sourceSystem: String = ONCHAIN_SOURCE_SYSTEM` to `OnchainTransfer`, `OnchainBalance`, `OnchainEvidence` (mirroring the existing defaulted `chain` field); the store writes `t.sourceSystem`. Solana behaviour unchanged.
- `newestSignature(chain, wallet)` is a Solana `until` cursor → add `newestStagedSlot(chain): Long?` (max staged `slot` per chain) for the EVM block cursor.
- Balance snapshots need the registered contract set → add `tokenContracts(chain): List<TokenContract>` reading `mesta.instrument` rows with non-null `mint_address` (`mint_address`, `decimals`).

**Files:** `OnchainTypes.kt`, `OnchainStagingStore.kt`, `JdbcOnchainStagingStore.kt`, `OnchainIngestionConfiguration.kt` delegate, `OnchainStagingStoreIT`.

**Done when:** Solana tests untouched and green; new store methods covered in `OnchainStagingStoreIT`.

### ARB-4 — EVM RPC core (`onchain/evm/`, T2)

- Promote `HttpTransport`/`TransportResponse`/`RetryPolicy` from `helius/` to a shared `onchain/` file — they are not Helius-shaped.
- `EvmConfig`: `rpcBaseUrl` (https enforced, key redacted from `toString`), `chain`, `chainId`, `sourceSystem`, `startBlock`, `maxBlockWindow`.
- `EvmRpcApi` + `EvmRpcClient`: `eth_chainId` (startup sanity check against config — a wrong-endpoint deploy fails loud), `eth_getBlockByNumber(tag)` for head + timestamps, `eth_getLogs`, `eth_getBalance`, `eth_call` (`balanceOf`, `decimals`, `totalSupply`). Every block-scoped call pins `"finalized"`.

**Done when:** unit tests against a fake `HttpTransport` cover retry, RPC-error, and finalized-tag assertions. Split the PR if transport promotion pushes it past 400 lines — promotion first, client second.

### ARB-5 — `EvmTransferNormalizer` + `EvmScanService` (T2)

- Log → `OnchainTransfer`: `topics[0]` = `Transfer(address,address,uint256)`; `mint_address` = emitting contract (lowercase); `slot` = `blockNumber`; `signature` = `txHash`; `block_time` from `eth_getBlockByNumber` (cached per run); `tokenAccount` = null; `counterparty` = the other side.
- `external_id` = `arbitrum-one:<txHash>:<wallet>:log:<logIndex>` — wallet-scoped because one log can serve two watched wallets; from = to = watched collapses to a single `self` leg.
- Kinds: `from == 0x0` → `mint`; `to == 0x0` → `burn`; else `transfer-in`/`transfer-out`/`self`. Receipt `status != 0x1` legs are never staged.
- Decimals: registry (`tokenContracts`) → `decimals()` `eth_call` cached per run → leg skipped and counted (never guessed).
- `EvmScanService`: window `newestStagedSlot + 1 → finalized head`, chunked by `maxBlockWindow`, shrinking on provider range errors; two `eth_getLogs` per window (from-side, to-side); merge by `(txHash, logIndex)`.

**Done when:** unit tests cover all five kind/direction branches, two-watched-wallet logs, unknown-contract skip, cursor resume, window pagination, and replay idempotency. Two PRs expected (normalizer, then scan service).

### ARB-6 — `EvmBalanceCollector` (T2)

- Per watched wallet: `eth_getBalance(wallet, 'finalized')` → `mintAddress = null`; `balanceOf` `eth_call` per `tokenContracts(chain)` → `mint_address` = contract.
- `source = 'rpc'`, `slot` = finalized block number, `asOf` = block timestamp — deterministic re-observation dedupes via the existing snapshot externalId formula.

**Done when:** tests mirror `OnchainBalanceCollectorTest` (per-wallet failure isolation, zero-balance skipping, snapshot shape).

### ARB-7 — Wiring, boundary rule, metrics (api, T1)

- `application.yml`: `mesta.onchain.evm.arbitrum-one.{rpc-url, start-block, poll-ms}`; `.env.example` gains the names only.
- `EvmIngestionConfiguration` with `@ConditionalOnProperty` on the rpc-url; `EvmSyncRunner` on `@Scheduled(fixedDelayString)` mirroring `ReportRunner` — nothing today calls `syncAll()`/`collect()`, so this phase also establishes the onchain scheduling pattern.
- `ModuleBoundaryTest` gains the `evm` vendor-package clause mirroring the `helius` one.
- Micrometer in the runner only (services return report objects; `ingestion` stays Micrometer-free): blocks scanned, legs staged, contracts skipped, cursor lag gauge, snapshot failures.

**Done when:** a disabled-by-default property set boots cleanly; boundary test covers `com.mesta.asset.ingestion.onchain.evm`.

### ARB-8 — `EvmEvidenceAdapter` (T2)

- `treasury-balance` → `eth_getBalance`/`balanceOf`; `token-supply` → `totalSupply`; `account-activity` → `eth_getTransactionCount` + bounded log count. `holder-concentration` returns a documented unsupported result — no plain-RPC equivalent exists.
- `ClaimVerifier` untouched; evidence dedupe by the existing externalId formula.

**Done when:** adapter tests mirror `OnchainEvidenceAdapterTest`; verifier tests need no changes.

### ARB-9 — Verification and rollout (T0/T1)

- Optional manual Sepolia (421614) smoke: watch a known address, replay a known ERC-20 transfer end-to-end (stage → promote → reconcile).
- Runbook: registering an ERC-20 instrument, watching an address, reading recon reports, and the **ETH recon-divergence caveat** (v1 has no ETH legs).
- Docs: update the status lines in `arbitrum-fit-assessment.md` and `arbitrum-ingestion-design.md`, README capability line.
- File follow-up issues: ETH trace legs (provider-gated), bridge in-transit NAV state, `holder-concentration` indexer, multi-EVM fan-out.

## Dependency order

```text
ARB-0 → ARB-1 → ARB-3 → ARB-4 → ARB-5 ┐
              ARB-2 (ontology, parallel)   ├→ ARB-7 → ARB-8 → ARB-9
                            ARB-6 ────────┘
```

ARB-1 must merge before any staging test can insert a `0x` row; ARB-3 before the adapter can write; ARB-2 can proceed in parallel (CTO-owned). ARB-5 and ARB-6 are independent once ARB-4 lands.

## Risks

- **Finalized-head lag (~12–15 min on One)** — acceptable for the reporting cadence; surfaced in the runbook, not hidden.
- **Provider `eth_getLogs` caps** — `maxBlockWindow` + shrink-on-error keeps it config, not code.
- **Spam/dust volume** on watched treasuries — stages and quarantines by design; watch paid-provider spend.
- **ArbOS upgrades** change transaction/log shapes — pinned Sepolia fixtures replay before upgrades reach One (inherited from the exchange-integration checklist).
- **Interface churn in ARB-3** — `OnchainStagingStore` gains methods while Solana flows depend on it; defaults keep every existing call site compiling.
