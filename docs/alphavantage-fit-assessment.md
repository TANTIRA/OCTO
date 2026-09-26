# Alpha Vantage — fit assessment

Evaluation of [Alpha Vantage](https://www.alphavantage.co/documentation/) as a market-data source for Mesta-Asset. Read on 2026-09-26. Unlike the earlier assessments an adapter slice already exists as scaffolding (`modules/ingestion/.../marketdata/`): it is not wired to a scheduler, pulls nothing until the gates below clear, and is written so deleting it is a one-directory revert.

- **Risk tier:** T0 for this document. T2 for the ingestion adapter, because it writes market data that analytics treats as financial fact.
- **Status:** proposed, not approved. Blocked on the licence question below and on the same instrument-concept gap as [xStocks](xstocks-fit-assessment.md), [Arbitrum](arbitrum-fit-assessment.md), and [TradingView](tradingview-mcp-fit-assessment.md).

## What it is

A plain REST catalog — over 130 endpoints behind one `GET /query` endpoint, keyed by `function`. Nine asset groups: equity time series, index data, options, "Alpha Intelligence" (news, transcripts, insider and congress trades), fundamentals, FX, crypto, commodities, and economic indicators. The adapter implements the three daily series:

| Function | Shape | In scope? |
| --- | --- | --- |
| `TIME_SERIES_DAILY` | OHLCV per trading day, `compact` = last 100 bars | Yes — benchmark levels, comparables |
| `FX_DAILY` | OHLC per day for a currency pair | Yes — currency translation |
| `DIGITAL_CURRENCY_DAILY` | OHLCV + market cap per day, quoted in a market currency | Yes — USD marks for token positions |
| `GLOBAL_QUOTE`, intraday, weekly/monthly | Lighter/finer-grained series | Deferred — daily granularity suffices v1 |
| Index endpoints (`S&P 500`, `DJIA`, …) | Index levels | Deferred — several are premium-gated |
| News & sentiments, transcripts | Text + scores | Deferred — a Control Panel tool, not an adapter feed |
| Fundamentals, earnings, calendars | Company financials | Deferred — comparables source candidate |
| Options, technical indicators | Derived series | No — analytics computes its own |

Two API quirks the adapter must absorb:

- **Errors arrive as HTTP 200.** A bad parameter, an exhausted daily quota, or a premium-gated function returns a JSON envelope (`Error Message`, `Note`, `Information`) with a success status. The client sniffs the body before treating a response as data.
- **The key travels in the URL.** `apikey` is a query parameter, so any logged request URI leaks the credential. Exceptions and logs must never carry it.

## The licence question comes first

The free key is evaluation-scale — roughly 25 requests per day — and its terms do not cover sustained commercial use. Premium plans raise the rate budget, and Alpha Vantage sells commercial terms, but nothing in the docs grants a platform sold to funds the right to store and re-serve the data.

Mesta-Asset writes vendor values into the bi-temporal `timeseries_observation` store and lets analytics derive benchmarks and valuations from them — redistribution-adjacent, exactly the use the free terms exclude.

**Nothing below matters until a written licence or premium plan exists.** Same gate as TradingView; Alpha Vantage is the friendlier vendor (premium tiers are priced for programmatic use) but the signature is still required.

## Where the endpoints would fit, if licensed

| Endpoint | Mesta use | Methodology or doc |
| --- | --- | --- |
| `TIME_SERIES_DAILY`, index levels | Public-market benchmark levels for KS-PME and Direct Alpha (§2.4–2.5); public comparables in the value bridge (§4.3) | `analytics/Performance.kt` takes a `Map<LocalDate, BigDecimal>` today |
| `FX_DAILY` | Currency translation for multi-currency portfolios and LP reports | `analytics/Bridge.kt` |
| `DIGITAL_CURRENCY_DAILY` | USD marks for token positions — the onchain pipeline derives units, never prices | `modules/ibor-core` positions |
| `OVERVIEW`, financial statements | Peer multiples and operating benchmarks (§5.3) | `analytics/Comparables.kt` takes `PeerMultiple`s |
| Economic indicators | Macro covariates for regime models and scenario analysis (§9.2, §3.4) | no slice yet |
| News & sentiments, transcripts | News matching for the Control Panel, diligence material for deal sourcing | `docs/decision-model-integration-map.md` |

## What it does not solve

- **No instrument concept.** The ontology has onchain instruments only; an equity ticker has nowhere to attach. The same CTO-owned, T2 blocker as the other vendor assessments (#109).
- **Point-in-time data is not guaranteed.** Nothing in the docs addresses restatements or as-of retrieval. The adapter relies on what the platform controls: `recorded_at` is assigned at ingestion, never from the vendor payload (V12 does this).
- **Thin lineage.** Payloads carry no dataset version or source identifier. Provenance is the platform's own columns — `source_system = 'alphavantage'`, run and correlation ids — recording function and arguments at call time.
- **Batch-only by budget.** The request cap makes this a scheduled daily pull, never a request-time lookup. `outputsize=compact` is hard-coded in the client; a `full` backfill is a separate one-shot tool.
- **Polling only.** No webhooks; freshness is one observation per series per day.

## Governance rules that apply

- **`ALPHA_VANTAGE_API_KEY` is a Confidential credential:** environment variable only (`.env.example`, `infra/.env.example`), redacted from `toString`, never in a log or a prompt. The config rejects non-HTTPS base URLs.
- **Symbol arguments are Confidential on the wire.** A ticker list reveals which companies the fund tracks — deal-flow information. The transport carries only symbols and currencies, never tenant data, positions, or text.
- **Read-only surface.** The API is pull-only; nothing in the adapter can act, so no tool-allowlist question arises. If the intelligence endpoints later feed an agent, that agent inherits the Control Panel's allowlist and cost rules.

## Architectural placement

The same shape as every vendor: Alpha Vantage formats stop at the adapter, and nothing outside `marketdata.alphavantage` references `function=` names or `"4a. close (USD)"` keys. `MarketDataPoint`/`MarketDataTarget` are the vendor-neutral types, mirroring the `onchain/helius` split; `HttpTransport` and `RetryPolicy` moved to a shared `ingestion.http` package so no vendor package imports another.

```text
Alpha Vantage ──► marketdata.alphavantage ──► mesta.dataset + timeseries_observation ──► analytics:
  GET /query      adapter (this slice)        (V12 bi-temporal store)                    benchmark levels,
 (daily, read-                                                         FX translation,
     only)                                                             token marks
```

Unlike the TradingView assessment, the landing surface exists: slice 6 shipped `mesta.dataset` and `timeseries_observation`, so the adapter needs no schema change — only a registered dataset per tenant.

## Before adopting

- [ ] Legal: obtain a written licence or premium plan covering commercial use and storage in a platform sold to funds. Without it, stop here
- [ ] CTO: the listed-instrument concept in the ontology, shared with the xStocks, Arbitrum, and TradingView assessments (#109)
- [ ] Product: pick the first dataset — benchmark index levels for PME, FX pairs, or crypto marks for the onchain book
- [ ] Engineering: a dataset-registration flow, a scheduled job entry point, and a `full`-outputsize backfill mode
- [ ] Engineering: contract tests that pin the three function names and response envelopes — the API has no versioning guarantee

## Open questions

- Which benchmark indices do the LP reports use for PME? Index endpoints are premium-gated, so the answer sets the tier.
- Do the funds already license a market-data feed through their administrator or a terminal? A licensed feed avoids the licence question entirely, and the adapter pattern is the same.
- Is Alpha Vantage's daily close the right mark for token positions, or do treasury tokens need the venue's own close / a pricing oracle? The recon module already treats a stale mark as a divergence.
