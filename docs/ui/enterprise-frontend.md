# OCTO enterprise frontend: architecture and delivery status

This covers the Vestra × Mesta mega plan (`OCTO_VESTRA_MESTA_VESTRA_CRAWL_MERGED_MEGA_PLAN.md`), phases 0–8. Scope is `web/` only.

Related docs: tokens in [token-registry.md](token-registry.md), history in [enterprise-app-audit.md](enterprise-app-audit.md).

## Architecture (plan §4)

```text
web/
  app/app/…                  routes: thin server files with metadata; views are client components
  components/
    shell/                   app-shell, sidebar, topbar, command-menu, nav-config, shortcuts, shell-context
    page/                    PageHeader (6 variants), PageBody/PageContainer, PageSection, PageToolbar, Panel family
    ui/                      button (+LinkButton), badge (+Tag, Monogram), controls (Tabs, Segmented, inputs,
                             Checkbox, Switch, FilterChip, Field), overlay (Popover, Sheet, Tooltip, Menu, ConfirmDialog)
    data/                    DataTable, table-state (pure, unit-tested), table-controls, cells
    metric/                  MetricCard + MetricGrid, Delta, LineageDrawer
    chart/                   ChartShell, TrendChart, BarChart, RankingBars, DonutChart, WaterfallChart,
                             ScatterChart, Heatmap, Timeline, Funnel, Sparkline, core (ticks, tooltip, legend)
    object/                  ObjectHeader, ObjectMetadata, ObjectLinks
    workflow/                SeverityBadge, WorkflowStatus, WorkflowStepper, WorkItem, DecisionPanel, AuditTrail
    ai/                      VerificationBadge, AiConfidence, AiCitation, AiDraftCard
    feedback/                Skeleton, Empty, Error, Permission, Freshness/Stale, InlineAlert, Toast
    views/                   one file per page
  lib/
    demo/                    reconciled demo ontology (funds → investments → companies, deals, ops, series)
    data/queries.tsx         TanStack Query hooks, query-key factory, stale time per data class
    format.ts, use-format.ts formatting in en and id
    preferences.tsx          theme, density, sidebar, period, locale
```

## Dependencies added

The plan asks for this justification in §3.2.

| Package | Replaces or enables |
| --- | --- |
| `@tanstack/react-query` | The typed data layer (§34): cache, stale time by data class, and "Refresh data" through invalidation |
| `@tanstack/react-virtual` | Virtualised table rows (§21); `DataTable` switches to it above 120 rows |
| `vitest` (dev) | Unit tests for formatting, table state and dataset reconciliation (§35) |
| `@playwright/test`, `@axe-core/playwright` (dev) | E2E coverage of primary workflows and the accessibility gate (§29, §35). Uses the installed Chrome channel, so no browser download |

Not added:

- **Recharts and ECharts.** The live Vestra bundle uses hand-rolled SVG, and OCTO's chart system covers line, area, bar, stacked, donut, waterfall, scatter, heatmap, timeline and funnel with tooltips, cursor, keyboard and accessible tables.
- **cmdk and Radix.** The existing command menu and overlays already meet the keyboard and ARIA requirements.

## Page status (plan §38)

| Route | Delivered |
| --- | --- |
| `/app` | KPI row with lineage; unified priority feed across 8 queues with detail sheets; attention counts; NAV trend; activity; signals; fund performance |
| `/app/portfolio` | KPI strip; value chart (fund, range, benchmark, value/change); allocation donut; 4 attention modules; movers; exposure ranking; intelligence; holdings grid; fund table |
| `/app/funds`, `/app/funds/[id]` | Fund cards, comparison and vintage chart. The fund object page has an 8-KPI band, NAV trajectory, value bridge, cash-flow timeline, holdings, attribution and activity |
| `/app/investments` | Filter-first grid with 9 facets, grouping, saved views, row actions, marks and a detail sheet |
| `/app/companies`, `/app/companies/[id]` | Directory, plus a 10-tab dossier. Drilling in from a fund keeps that fund in the breadcrumb |
| `/app/deals`, `/app/deals/[id]` | Board, table and timeline views, with calendar marked planned; funnel; stage moves via menu. The detail page has 8 tabs with a stage stepper |
| `/app/reconciliation` | Break queue and a side-by-side drawer showing mapping, lineage, previous resolution, ledger events and the audit trail. Resolution always needs a reason |
| `/app/workflows` | Tasks (create, bulk update), approvals (stepper, required comment), exceptions, AI drafts |
| `/app/alerts` | Inbox (acknowledge, assign, snooze, resolve, open object or rule) and the rules catalogue (conditions, sources, backtest, activation, version) |
| `/app/analytics` | Main chart with metric switcher and rail; bridge; risk/return scatter; sector heatmap; exposure |
| `/app/reports`, `/app/reports/[id]` | Report centre with 6 status tabs; builder split view with structure, bindings, citations, validation, approval and versions |
| `/app/data` | Sources, ingestion health, mappings, lineage |
| `/app/settings` | 18 sections: personal (4), workspace (9), governance (5) |

The legacy blocks `dashboard-4`, `data-table-3`, `kanban-1`, `app-shell-2` and `command-menu-1` are removed.

## Quality gates

Tests:

- **Unit** (`npm test`): 24 tests.
  - Formatting in en and id.
  - Table filter, sort, group and view encoding.
  - Dataset reconciliation: fund NAVs sum to $812.4M, TVPI is 1.64× and DPI is 0.38×.
- **E2E** (`npm run build && npm run test:e2e`): 11 workflows.
  - Navigation, search, drill-down, shared views, table sort and bulk actions, approval and recon reasons, theme and density, lineage, mobile drawer.
- **Accessibility**: axe runs on 17 routes in both light and dark mode. It fails on serious or critical violations and passes today.

Typecheck is `npm run typecheck`.

## Known limitations

- **Demo data.** All portfolio, operational and back-office data is demo data, clearly labelled. Only workspaces come from `/api/v1/me/access`.
- **Decisions are not persisted.** Approvals, resolutions and stage moves live in session state until workflow endpoints exist.
- **Stage moves.** Deals move between stages through an accessible menu; drag and drop is not implemented.
- **Visual regression.** The screenshot baseline is manual (light, dark and mobile checked); automated visual regression is not wired into CI.
- **Localisation scope.** Only numbers and dates are localised to `id`; UI copy is English.
