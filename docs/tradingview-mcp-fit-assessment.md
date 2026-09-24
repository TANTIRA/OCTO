# TradingView MCP server — fit assessment

Evaluation of the [TradingView MCP server](https://www.tradingview.com/mcp/docs) as a market-data, fundamentals, news, and calendar source for Mesta-Asset. Documentation-only: no tool was called, no account was connected, and no data was pulled. Read on 2026-09-24, together with the [TradingView terms](https://www.tradingview.com/policies/), which matter more than the tool list.

- **Risk tier:** T2 for any ingestion adapter, because it writes market and financial data, and for any AI feature that reads it. Tool-server review by the Security Blue Team first, per AGENTS.md.
- **Status:** proposed, not approved. Blocked on the licence question below and on the same instrument-concept gap as [xStocks](xstocks-fit-assessment.md) and [Arbitrum](arbitrum-fit-assessment.md).

## What it is

A hosted MCP server at `https://mcp.tradingview.com/mcp`, authenticated with OAuth 2.1 against a personal TradingView account. It is included in the Essential plan and above, not in trials. Calls are rate-limited to about 100 requests per minute per user, and the tool set is in beta.

35 tools in nine groups:

| Group | Tools | Read or write |
| --- | --- | --- |
| Watchlists | list, get, get active, create, delete, add, remove, update | 3 read, 5 write |
| Market data | OHLCV bars (1m to monthly, up to 5,000 bars), economic time series, economic symbol catalog | read |
| Symbol search | fuzzy search to `EXCHANGE:TICKER` with a type filter | read |
| Screener | per-symbol columns, batch of up to 50 symbols, a filterable screener, column catalog, technical ratings | read |
| News | headlines per symbol, full story text | read |
| Fundamentals | analyst consensus and price targets, current fundamentals, quarterly and annual history | read |
| Documents | filings and transcripts (10-K, 10-Q, 8-K, earnings calls), document text | read |
| Calendars | earnings, macro events, dividends | read |
| Alerts | create, update, delete, stop, restart, list, get, fire log | 3 read, 5 write |

## The licence question comes first

The terms say the content and market data on the platform, "including but not limited to charts, alerts, webhooks, and any other forms of information, are licensed for exclusive display-only use", limited to "personal or internal business purposes". Prohibited non-display uses include "price referencing", "algorithmic decision-making", and "using data in operations control or risk management programs". Third parties may not "create, offer, or operate any product or service" that relies on TradingView data for non-display purposes. Commercial use of the services or APIs needs a separate agreement.

Mesta-Asset is a platform sold to funds. Its analytics reference prices (§2.4 KS-PME needs benchmark levels), its risk measures are risk management (§3), and its screening is decision support (deal sourcing). Feeding TradingView data into any of that is the non-display use the terms exclude. Storing it in the IBOR or a bi-temporal data service is redistribution to the platform's users.

**Nothing below matters until a written data licence exists.** The MCP server does not change the licence; it changes the client.

## Where the tools would fit, if licensed

| Tool group | Mesta use | Methodology or doc |
| --- | --- | --- |
| OHLCV bars, index levels | Public-market benchmark levels for KS-PME and Direct Alpha (§2.4–2.5), public comparables in the value bridge (§4.3) | `analytics/Performance.kt` takes a `Map<LocalDate, BigDecimal>` today |
| Screener columns, fundamentals, financial history | Peer multiples for §5.3 comparables, market inputs to WACC (§5.2), operating benchmarks for portfolio companies | `analytics/Comparables.kt` takes `PeerMultiple`s |
| Economic data and calendar | Macro covariates for regime models (§9.2) and scenario analysis (§3.4, §7.3) | no slice yet |
| News, documents, transcripts | News matching for the Control Panel (decision point 9), diligence material for deal sourcing | `docs/decision-model-integration-map.md` |
| Watchlists and alerts | None. The platform has its own alert rules (WF-5) and its own workflow; a TradingView alert would act outside the audit log | — |

## What it does not solve

- **No instrument concept.** The ontology has no listed instrument or security type. Every tool keys on `EXCHANGE:TICKER`, which has nowhere to attach. This is the xStocks blocker again, and it is CTO-owned and T2.
- **Point-in-time data is not guaranteed.** `get_financial_history` returns reported values with period labels, but the docs say nothing about restatements or as-of retrieval. §10.8 and §6.2 require point-in-time inputs for backtests and forecasts. Anything ingested needs `recorded_at` set at ingestion, never a vendor timestamp.
- **Lineage is thin.** Responses carry no dataset version or source identifier beyond the provider name on news. §10.6 needs every number traceable to its source record. An adapter would have to record the tool, the arguments, and the call time as the source record itself.
- **Personal accounts.** OAuth is against a person's TradingView subscription, on personal or internal-use terms. A platform cannot run on an employee's login: it creates a key-person dependency and a licence breach at once.
- **Beta.** The tool set "expands over time" and the terms give no backward-compatibility guarantee for the API. Pin the tool names and argument schemas in the adapter's contract tests, as the Arbitrum assessment does for RPC upgrades.

## Governance rules that apply

- **Third-party MCP servers need Security Blue Team review before use** (AGENTS.md). This server is remote, hosted by the vendor, and authenticates with OAuth. It does not appear on the approved list in repo TANTIRA.
- **Confidential data must not leave the platform.** Every tool argument goes to TradingView. A symbol search or a screener query reveals which companies the fund is looking at, which is deal-flow information and at least Confidential. The `DataClassification` guard that protects the decision-model client would need to cover this transport too.
- **Write tools are out.** Creating watchlists or alerts on a personal account writes user data outside the platform's audit log (`mesta.audit_event`). An agent tool allowlist must exclude all ten write tools.
- **Agents that act need an allowlist, cost limits, and human approval.** The MCP server hands an agent 35 tools at once; the platform must expose only the read tools it has licensed.

## Architectural placement

The same shape as every vendor. TradingView formats stop at an adapter, and nothing outside it depends on `EXCHANGE:TICKER` or on screener column names.

```text
TradingView MCP ──► ingestion adapter ──► staging ──► bi-temporal data service (slice 6)
 (read tools only)          ▲                           │
                    formats stop here                   └──► analytics: benchmark levels, peer multiples
```

Two ways to call it:

- **As an ingestion adapter** (recommended): a scheduled job with a service credential, calling a fixed set of read tools, writing to staging with `recorded_at`. Deterministic, auditable, and rate-limit friendly.
- **As an agent tool**: an LLM in `control-panel` picks tools at run time. Faster to build, but every argument is a data-leak surface and every call is a non-deterministic input to a financial number. Only for the Control Panel's news matching, and only with the allowlist above.

## Before adopting

- [ ] Legal: obtain a written data licence from TradingView (or its data providers) that permits non-display, commercial use in a platform sold to funds. Without it, stop here
- [ ] Security Blue Team: review the MCP server as a third-party tool server and add it to the approved list, or reject it
- [ ] CTO: the instrument concept in the ontology, shared with the xStocks and Arbitrum assessments
- [ ] Platform: a service credential and plan, not an employee's account
- [ ] Product: decide whether the first use is benchmark levels for PME (small, deterministic) or news for the Control Panel (an agent tool)
- [ ] Engineering: adapter contract tests that pin tool names and schemas, and a `DataClassification` check on every outbound argument

## Open questions

- Is there a market-data provider the funds already license, such as one their administrator uses? A licensed feed would avoid the licence question entirely, and the adapter pattern is the same.
- Which benchmark indices do the LP reports use for PME? That decides whether OHLCV on an index symbol is enough, or whether total-return series are needed, which the tool list does not mention.
- Does anyone on the team hold a TradingView plan today, and for what? That determines whether this is exploratory or already in use outside the platform.
