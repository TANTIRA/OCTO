# User Workflows

End-to-end operating workflows for Mesta-Asset, derived from the platform blueprint and reference screens (Portfolio Overview, Fund Metrics, Investment Metrics, Company Details, Investment Control Panel).

## Personas

| Persona | Uses the platform to |
| --- | --- |
| **Sourcing / Research Analyst** | Screen inbound opportunities, complete diligence, and prepare source-grounded IC reports |
| **Portfolio Manager (PM)** | Monitor portfolio performance, drill from fund to deal to company, answer ad-hoc questions |
| **Investment Analyst** | Review portfolio-company financials, track hiring/operating metrics, write memos |
| **Operations** | Onboard data sources, resolve reconciliation discrepancies, manage data quality |
| **IR / LP Relations** | Produce LP reports and tear sheets, respond to LP requests |
| **CFO / Oversight** | Look-through exposure, control-panel oversight, approve outbound artifacts |

## WF-1 — Daily monitoring (Control Panel first)

```mermaid
flowchart TD
  A[User opens Investment Control Panel] --> B[Review AI-generated alerts<br/>triggered rules, news matches]
  B --> C{Action needed?}
  C -->|No| D[Dismiss / mark read]
  C -->|Yes| E{Response type}
  E -->|Question about data| F[NL query against fund data<br/>'What is net income vs CoGS<br/>for US Manufacturing III?']
  E -->|Follow-up| G[Review AI-drafted email/memo]
  F --> H[Result renders as chart/table<br/>drill to source entities]
  G --> I{Approve?}
  I -->|Edit| J[Edit draft in place]
  I -->|Approve| K[Send / file to CRM]
  I -->|Reject| L[Discard — logged for eval set]
  J --> K
  E -->|Assign work| M[Create task in workflow<br/>assignee + due date]
```

## WF-2 — Portfolio drill-down (overview → deal → company)

```mermaid
flowchart LR
  A[Portfolio Overview<br/>Total invested, drawdown,<br/>Gross/LP Net IRR, TVPI] --> B[Fund Metrics<br/>sector IRR/MOIC/TVPI,<br/>equity bridge]
  B --> C[Investment Metrics<br/>filter by deal source,<br/>status, keyword]
  C --> D[Company Details<br/>profile, mgmt team,<br/>financial metrics, hiring trends]
  D --> E[Export accounting data /<br/>financial metrics / company info]
  C --> F[NL query at any level]
  F -.->|answer cites entities| B
```

## WF-3 — Data onboarding (Operations)

```mermaid
flowchart TD
  A[Register source — CRM,<br/>financial feed, doc store] --> B[Configure adapter mapping<br/>source schema → Ontology]
  B --> C[First load → staging]
  C --> D{SHACL validation}
  D -->|Pass| E[Write to IBOR ledger]
  D -->|Fail| F[Reconciliation queue]
  E --> G{Recon vs source<br/>snapshot}
  G -->|Match| H[Source marked healthy<br/>scheduled refresh]
  G -->|Discrepancy| F
  F --> I[Ops user inspects —<br/>source value vs IBOR value<br/>side by side]
  I --> J{Resolution}
  J -->|Fix mapping| B
  J -->|Correct source| K[Push correction task<br/>to source owner]
  J -->|Accept IBOR| L[Record override —<br/>audited, reason required]
```

## WF-4 — LP reporting (IR)

```mermaid
flowchart TD
  A[Select reporting period + LP/fund scope] --> B[Generate artifacts —<br/>tear sheets, LP report,<br/>quarterly metrics pack]
  B --> C[Review in preview —<br/>charts, tables, commentary]
  C --> D{Edits?}
  D -->|Commentary| E[AI-drafted commentary<br/>user edits inline]
  D -->|Data looks wrong| F[Trace metric → lineage<br/>to ledger entries]
  E --> G[Submit for approval]
  F -->|real discrepancy| H[Route to recon queue]
  F -->|resolved| C
  G --> I{Approver sign-off<br/>workflow}
  I -->|Approved| J[Publish — LP portal /<br/>email / export]
  I -->|Changes requested| C
```

## WF-5 — Alert rule authoring

```mermaid
flowchart LR
  A[Define rule — NL or structured:<br/>'alert when net income / CoGS<br/>drops below threshold for<br/>any company in fund X'] --> B[Compile to evaluable rule<br/>against Ontology entities]
  B --> C[Test against historical data<br/>show would-have-fired events]
  C --> D{Satisfied?}
  D -->|No| A
  D -->|Yes| E[Activate — runs on every<br/>IBOR write / scheduled eval]
  E --> F[Fires → appears in<br/>Control Panel inbox]
```

## WF-6 — Metric/model authoring (low-code)

```mermaid
flowchart TD
  A[Author metric definition —<br/>e.g. custom MOIC variant,<br/>forecast, valuation model] --> B[Versioned in Git<br/>as metric DSL]
  B --> C[Backtest on ledger history]
  C --> D{Results sane?}
  D -->|No| A
  D -->|Yes| E[Publish to metrics engine]
  E --> F[Available in dashboards,<br/>NL query, artifacts, alerts]
```

## App UI flow

Top-level navigation — six tabs, persistent header:

```mermaid
flowchart TD
  subgraph NAV[" "]
    direction LR
    DP["① Deal Pipeline"]
    PO["② Portfolio Overview"]
    FM["③ Fund Metrics"]
    IM["④ Investment Metrics"]
    CD["⑤ Company Details"]
    ICP["⑥ Investment Control Panel"]
  end

  DP -->|IC-approved prospect converts to investment| IM
  PO -->|click fund row| FM
  FM -->|click fund row| IM
  IM -->|click investment row| CD
  ICP -->|"alert references fund/company"| FM
  ICP -->|"alert references company"| CD

  subgraph POs["Portfolio Overview"]
    POa[KPI band — invested,<br/>drawdown, IRRs, TVPI]
    POb[Capital waterfall]
    POc[Cash-flow timeline]
    POd[Sector allocation pie]
    POe[IRR time series]
    POf[Fund table]
  end
  PO -.-> POs

  subgraph FMs["Fund Metrics"]
    FMa[KPIs — Gross/Net IRR,<br/>TVPI, MOIC, G/L]
    FMb[Sector IRR/MOIC/TVPI bars]
    FMc[Equity bridge<br/>entry → exit]
    FMd[Sector rollup table]
    FMe[Fund table]
  end
  FM -.-> FMs

  subgraph IMs["Investment Metrics"]
    IMa[Filter rail — keyword,<br/>deal source, status]
    IMb[Cash-flow + MOIC chart]
    IMc[KPIs — committed,<br/>invested, IRRs]
    IMd[Realized/Unrealized<br/>summary]
    IMe[Investment table]
  end
  IM -.-> IMs
  IMa -.->|filters all panels| IMb & IMc & IMd & IMe

  subgraph CDs["Company Details"]
    CDa[Left rail — description,<br/>founding, co-investors,<br/>our investment, contacts]
    CDb[Data export — accounting,<br/>metrics, company info]
    CDc[Company metrics panel]
    CDd[Key financial metrics chart]
    CDe[Hiring trends chart]
    CDf[Management team cards]
  end
  CD -.-> CDs

  subgraph ICPs["Investment Control Panel"]
    ICPa[Query bar —<br/>'Question the data']
    ICPb[Manage AI rules]
    ICPc[Alert inbox —<br/>triggered rules]
    ICPd[Drafts — AI emails/memos]
    ICPe[News feed —<br/>matched articles]
    ICPf[Email inbox —<br/>create follow-ups]
  end
  ICP -.-> ICPs

  ICPa -->|answer + cited entities| IM
  ICPd -->|approve → send| OUT[Outbound —<br/>via workflow approval]
  ICPf -->|"reply / follow-up"| ICPd
  ICPe -->|"relevance triggers"| ICPc
```

Navigation rules:

- **Drill-down is row-driven** — clicking a table row descends one level (portfolio → fund → deal → company). Every level keeps the tab bar; breadcrumb shows the entity path.
- **Alerts deep-link** — a triggered rule in the Control Panel opens the affected fund/company directly at the right tab.
- **Query bar is global** — NL query answers inline and links into the relevant tab with filters pre-applied.
- **Filters cascade** — the Investment Metrics filter rail scopes every panel on that tab; selections persist when drilling to Company Details.

## Cross-cutting rules

- **Everything outbound passes workflow approval** — LP reports, drafted emails, memos. AI proposes; humans send.
- **Every number is traceable** — any metric drills to the ledger entries and source documents behind it.
- **Overrides are audited** — accepting an IBOR value over a source value requires a recorded reason.
- **NL query never bypasses permissions** — results are scoped to the user's entity access.
