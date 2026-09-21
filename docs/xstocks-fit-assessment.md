# xStocks — fit assessment

Evaluation of [xStocks](https://docs.xstocks.fi) as an instrument or data source for Mesta-Asset. No API calls were made; this is a documentation-only assessment based on the published docs.

- **Risk tier:** T3 for anything touching issuance, redemption, or keys (mainnet contracts, key management). T1 for read-only public market metadata
- **Status:** proposed, out of current scope. The platform models private-market entities and has no instrument concept

## What xStocks is

Tokenized US equities and ETFs. Each token is fully collateralized 1:1 by a share held with a regulated custodian, issued by Backed, and freely transferable onchain.

| Layer | Behaviour |
| --- | --- |
| Primary market | Issuance and redemption directly with the issuer. Requires KYC/AML onboarding. Runs 24/5, aligned to the underlying equity market. Settles in stablecoins or in-kind via xPort |
| Secondary market | Permissionless trading across exchanges, wallets, and DeFi. Runs 24/7 on some venues, no issuer interaction |
| Networks | Solana, Ethereum, Arbitrum, Mantle, TON, Ink, and other EVM chains. A bridge moves tokens between them |

Corporate actions are handled by a **multiplier** rather than cash distribution. Dividends are reinvested into more underlying shares (`DVCA`), splits scale the multiplier up (`SPLF`), reverse splits scale it down (`SPLR`). Holders take no action.

## The asset-class gap

The ontology has no `instrument`, `security`, `token`, `wallet`, or asset-class type. `ontology/mesta-investment.tql` is entity-centric — fund, deal, investment, operating company, party, commitment, ledger event. Tokenized listed equity has nowhere to attach.

The platform has flagged this conditionally rather than ruled it out. `docs/quantitative-methodology.md` notes its listed-securities equations "apply when the platform supports listed securities, secondary transactions, FX hedges, or trade execution related to private-market operations." `ibor-core` is scoped to "corporate-action-equivalent events for **private** assets."

Adopting xStocks therefore means adding an asset class, not adding a data source. That is an ontology change: T2, CTO-owned, SemVer-versioned.

## Where the mapping is genuinely good

The multiplier mechanism is the interesting part, because it reproduces a problem the IBOR already solves.

| xStocks concept | IBOR equivalent |
| --- | --- |
| Raw amount — constant onchain, never changes on a corporate event | Ledger fact. Append-only, immutable |
| Multiplier change (`DVCA`/`SPLF`/`SPLR`) | Corporate-action-equivalent event |
| Scaled amount = raw × multiplier | Derived position. Computed, never stored |
| `getAccountInfo` multiplier on Solana/TON | Input to derivation |

The design principle matches exactly. Solana and TON leave the raw balance untouched and require the application to apply the multiplier for display — that is "positions are derived, never written." EVM chains take the opposite approach: the contract recalculates every holder's `balanceOf()` when the multiplier activates, which is the mutable-position model the IBOR deliberately rejects.

If xStocks were ever ingested, the correct shape is: raw amounts and multiplier changes enter as ledger events, and scaled balances are derived. Never store a scaled balance as a fact.

## Where it does not fit

| Excluded | Reason |
| --- | --- |
| Custody, issuance, redemption, wallets, keys | `AGENTS.md` puts mainnet contract work and key management at T3. Agents never hold a deployer wallet or key |
| The fund, LP, commitment, and portfolio-company model | Unaffected. A PE fund holds illiquid positions with no secondary market and no continuous price |
| Valuing private portfolio companies | A token price is not a valuation input for an unlisted company |
| Regulatory and licensing surface | Tokenized securities bring KYC/AML, custody, and licensing obligations. Those belong to the counterparty, not the platform |
| Trade execution | Out of scope per ADR-0001, which scopes the platform to portfolio management above the administrator and custodian layer |

## Reconciliation hazard

This is the concrete risk if balances are ever ingested, and it is exactly what `recon` exists to catch.

`balanceOf()` on EVM already includes the multiplier. The raw balance on Solana and TON does not. Ingesting a balance without recording which convention the source used will either double-apply or omit the multiplier, and the resulting position will be wrong by a factor that grows with every corporate action.

Any ingestion of these balances must persist the convention and the multiplier value observed at ingestion time, so a later recomputation can reproduce the number. A discrepancy that compounds silently across dividends is the worst case in an append-only ledger.

## Architectural placement

Read-only public market and token metadata is the only low-risk surface, and it belongs in an `ingestion` adapter like any other vendor.

```text
xStocks public endpoints ──► ingestion adapter ──► staging ──► ontology / ledger
                                    ▲
                        vendor formats stop here
```

ADR-0001's vendor-neutrality consequence applies directly: no module outside the adapter may depend on xStocks formats, chain-specific conventions, or endpoint shapes. Swapping or adding a tokenized-equity provider must be an adapter change.

The primary market — issuance, redemption, xPort in-kind flows — is not an adapter concern. It is T3 and out of scope.

## Before adopting

- [ ] Decide whether the platform supports listed or tokenized instruments at all. Until then this is out of scope
- [ ] If yes: an ontology change adding an instrument concept, with the raw/derived split modeled explicitly — T2, CTO-owned
- [ ] CTO and Security review of the T3 boundary before any issuance, redemption, or key handling is discussed
- [ ] Reject any design where a scaled balance is stored as a ledger fact
- [ ] Record the chain-specific balance convention and multiplier on every ingested balance

## Open questions

- Is there a product requirement for listed or tokenized exposure, or is this exploratory?
- Would xStocks serve as a data source, an instrument to be held, or neither?
- If only public market metadata is wanted, does a conventional market-data provider fit the existing adapter pattern with less complexity?
