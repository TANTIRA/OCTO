# User Interfaces

Interface inventory and per-screen spec for Mesta-Asset. Layouts derive from the reference screens; every panel lists its data elements and actions.

## Shell

```text
┌─────────────────────────────────────────────────────────────┐
│ Logo Firm [Pipeline][Overview][Fund][Investment][Company][CP]│
├──────────────┬──────────────────────────────────────────────┤
│  (optional    │  Main content region                         │
│   rail)       │                                              │
└──────────────┴──────────────────────────────────────────────┘
```

- Persistent tab bar: **Deal Pipeline · Portfolio Overview · Fund Metrics · Investment Metrics · Company Details · Investment Control Panel**
- Optional left rail per tab (filters, entity info, export)
- Global query bar accessible from every tab
- Breadcrumb shows entity path when drilled (`Portfolio → US Manufacturing III → FN NYC`)

## 1. Portfolio Overview

*Question answered: "How is the whole portfolio doing?"*

| Region | Components | Data elements | Actions |
| --- | --- | --- | --- |
| KPI band | 5 stat cards | Total Invested, Available for Drawdown, Portfolio Gross IRR, LP Net IRR, Portfolio TVPI | — |
| Waterfall | Bar chart | FMV unrealized, management fees, other expenses, paid-in, realized proceeds → total invested | Hover values |
| Cash flow | Grouped bars + line, by year | Contributions, Distributions, Net Cash Flow, Total Cash Flow | Period selector |
| Sector allocation | Pie/donut | Sector share of FMV | Click sector → filters table |
| IRR series | Multi-line, by year | Investor Net IRR, Fund Gross IRR, Fund Net IRR | Toggle series |
| Fund table | Data grid | Title, Manager, Initial Fund Date, Sector, Total Committed, Total Invested, Available for Drawdown, Unrealized G/L, Total Realized G/L, Net IRR, Remaining Cost, Gross IRR, Gross MOIC, Country | Sort, row-click → Fund Metrics |

## 2. Fund Metrics

*Question answered: "Which funds/sectors drive performance, and how?"*

| Region | Components | Data elements | Actions |
| --- | --- | --- | --- |
| KPI band | 6 stat cards | Gross IRR, Net IRR, TVPI, MOIC, Unrealized G/L, Realized G/L | — |
| Sector performance | Grouped bars by sector | Gross IRR, Net IRR, Net MOIC, Net TVPI | Click sector → filters |
| Equity bridge | Waterfall | Entry Equity → Revenue Growth, EBITDA Margin, Debt Reduction, FX Impact, Other → Exit Equity | — |
| Sector rollup | Data grid | Sector, # Funds, Capital Committed, Available for Drawdown, Amount Invested, Remaining Cost, Realized G/L, Unrealized G/L, # Fund Managers | — |
| Fund table | Data grid | Title, Initial Fund Date, Country, Sector, Committed, Drawdown, Invested, Realized/Unrealized G/L, Remaining Cost, Gross IRR, TVPI, Gross MOIC, Net IRR | Row-click → Investment Metrics |

## 3. Investment Metrics

*Question answered: "How are individual deals performing?"*

| Region | Components | Data elements | Actions |
| --- | --- | --- | --- |
| Filter rail (left) | Faceted filters | Keyword search; Deal Source (Founder / Auction / Limited Auction); Status (Realized / Unrealized) | Filters cascade to all panels |
| Cash flow + MOIC | Bars + overlaid line, by period | Cash Flow, Gross MOIC | Period selector |
| KPI band | 4 stat cards | Total Committed Capital, Total Invested Capital, Gross IRR, Net IRR | — |
| IRR series | Dual-line | Gross IRR, Net IRR over time | — |
| Status summary | Data grid | Status, Capital Invested, Capital Committed, TVPI, Avg FMV, Avg Realized/Unrealized G/L, Remaining Cost | — |
| Investment table | Data grid | Investment Name, Fund Name, Deal Source, Status, TVPI, FMV, Gross IRR, Net IRR, Committed Capital, Amount Invested, Remaining Cost, Distributions, Unrealized G/L, Realized G/L | Multi-select; row-click → Company Details |

## 4. Company Details

*Question answered: "What's the full picture on this portfolio company?"*

| Region | Components | Data elements | Actions |
| --- | --- | --- | --- |
| Left rail | Info sections | Company Description; Founding Information; Other Investors; Our Investment (date, round, amount); Last Contact log; Contact Information (website, email) | — |
| Export panel | 3 buttons | Export Accounting Data, Export Financial Metrics, Export Company Information | Download (Excel/CSV) |
| Company metrics | Key-value grid | Fund Name, Country, Deal Source, Sector, Status, FMV, Committed Capital, Amount Invested, Remaining Cost, TVPI, Distributions, Unrealized G/L | — |
| Financial metrics | Stacked/grouped bars by period | CoGS % growth, Inventory OH growth %, Sales growth % | Metric selector |
| Hiring trends | Dual-line over time | Hired, Leavers | — |
| Management team | Cards | Role (CIO/GC/COO/CTO…), Name, Email, Start/End Date | Click → contact actions |

## 5. Investment Control Panel

*Question answered: "What needs my attention, and what can I delegate?"*

| Region | Components | Data elements | Actions |
| --- | --- | --- | --- |
| Header | User context, subtitle | "Viewing information for user: {name}" — alerting inbox for proactive & reactive tasks | — |
| Query bar | NL input + button | "Question {firm}'s data" | Answer inline; cited entities deep-link |
| Rules manager | Button → rule list | Manage my AI rules | Create/edit/test/activate rules (WF-5) |
| Existing alerts | Filterable list | Type filter, keyword filter; entries show type (Triggered Rule), content, timestamp, linked entities | Open → deep-link; dismiss; convert to task |
| Drafts | Inline previews | AI-drafted emails/memos: recipient, subject, body | Approve-send / edit / reject (WF-1) |
| News feed | Article cards | Headline, publisher, date, link, relevance badge | Open article; flag relevance → feeds matching |
| Email inbox | Message cards | Sender, subject, received date, content preview | Create follow-up → generates draft |

## 6. Deal Pipeline

*Question answered: "Which opportunities deserve attention, and what evidence supports the decision?"*

| Region | Components | Data elements | Actions |
| --- | --- | --- | --- |
| Pipeline board | Stage columns or table | Intake, Screening, Analyst Review, Due Diligence, IC Preparation, IC Review, Approved, Declined | Drag only when transition policy permits; open prospect |
| Pipeline filters | Facets and search | Owner, strategy, sector, country, source, stage, screening result, age, missing evidence | Save view; share with team |
| Intake | Upload/drop zone and integrations | Pitch decks, CRM records, emails, data-room documents | Upload; assign owner; deduplicate |
| Prospect summary | Identity and extracted facts | Company, description, sector, geography, revenue, growth, ownership, transaction, contacts | Edit with reason; open source citation |
| Screening panel | Criteria result list | Criterion, extracted value, source, confidence, pass/fail/unknown/conflicting, rule version | Confirm; request evidence; override with rationale |
| Document viewer | PDF/image plus extraction overlay | Page, text block, table cell, chart region, extracted claim | Jump from claim to source; correct extraction |
| DDQ workspace | Question/answer list | Standard and bespoke questions, suggested answer, citations, confidence, owner, state | Verify/edit; export unanswered; upload follow-up |
| IC report editor | Structured template and source panel | Thesis, market, financials, returns, risks, mitigants, value creation, open questions | Generate section; edit; trace source; submit for approval |
| Decision history | Immutable timeline | Stage transitions, rules evaluated, overrides, approvals, comments, model/template versions | Filter; export audit record |

Prospect progression follows [deal-sourcing-workflow.md](deal-sourcing-workflow.md). A prospect converts to an investment only after IC approval; all documents, screening results, DDQ answers, and citations remain attached as lineage.

## 7. Supporting surfaces (implied, not in reference screens)

| Surface | Purpose | Key elements |
| --- | --- | --- |
| Recon queue | Resolve discrepancies | Source value vs IBOR value side-by-side; mapping rule shown; fix-mapping / correct-source / accept-IBOR actions |
| Approval queue | Gate outbound artifacts | Pending artifacts with diff vs draft; approve / request-changes |
| Rule authoring | Create alert rules | NL or structured input; historical backtest preview; activation toggle |
| Screening criteria | Govern prospect screening | Strategy-scoped rule builder; effective dates; test cases; version history; approval |
| DDQ library | Standardize diligence | Question sets by strategy/sector/geography; required evidence; ownership; version history |
| Report templates | Govern IC and external reports | Structured sections; allowed Ontology fields; citation requirements; approval |
| Metric authoring | Low-code models | DSL editor; backtest results; publish to engine |
| Source admin | Manage adapters | Connected sources, last refresh, health status, mapping editor |
| LP portal | External artifact delivery | Published reports/tear sheets per LP scope; read-only |

## Shared components

- **Stat card** — label + compact value; optional sparkline; "why" lineage affordance on hover
- **Data grid** — sortable columns, row-click drill, multi-select for bulk actions; full-precision values
- **Entity chip** — linked entity references (fund, company) rendered as chips everywhere they appear (alerts, drafts, emails)
- **Alert card** — type icon, content, timestamp, linked entities, one obvious action
- **Provenance badge** — "drafted by AI" / "source: {provider}" / "as of {date}" on all generated or ingested data
