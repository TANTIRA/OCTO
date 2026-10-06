# Enterprise app: audit and phase 0–3 baseline

Scope: `web/app/app/**`, the signed-in application. The landing page (`/`) is out of scope and unchanged.

## What we found

| Area | Before | Problem |
| --- | --- | --- |
| Shell | `app-shell-2.tsx`, 1,131 lines, hash routes (`#assets`, `#recon`) | One file for navigation, pages and data. No URLs to share and no browser history. |
| Navigation | 6 flat areas | Did not match the IA. Funds, companies, analytics, data and reports had no entry point. |
| Theme | `neutral-*` classes plus `dark:` variants | No semantic tokens, so every block chose its own greys. |
| Tables | Hand-rolled per block | No shared sort, filter, density, export, or empty/error states. |
| Data honesty | Hardcoded rows shown as if live | No freshness or "demo" label anywhere. |
| Command menu | `command-menu-1.tsx` | Static list, no workspace switching or actions. |

## Token map

App tokens live in `web/app/globals.css`. Light values sit in `@theme`, and dark values override them under `.dark`. The `dark` class is only ever set on `.octo-app`, so the landing page is never affected.

| Role | Token | Light | Dark |
| --- | --- | --- | --- |
| App background | `app` | `#f6f6f3` | `#0e1014` |
| Card surface | `surface` | `#ffffff` | `#15181e` |
| Popover / sheet | `raised` | `#ffffff` | `#1b1f26` |
| Sidebar, table header | `subtle` | landing value | `#111318` |
| Hover | `hover` | `#f1f1ee` | `#1f232b` |
| Divider | `line` / `line-strong` | landing values | `#242830` / `#323741` |
| Text | `ink` → `ink-4` | landing values plus `#9a9ea6` | `#eceef1` → `#5f646e` |
| Accent | `accent` | landing value | `#8f73ff` |
| Status | `ok` `warn` `danger` `info` | landing values | brightened for contrast |

Type scale: `text-page` 22px, `text-object` 26px, `text-metric` 24px, and `text-label` 11px caps. App radii (2–8px) are set under `.octo-app`.

## What shipped in phases 0–3

- **Shell** (`components/layout/`): collapsible sidebar (232 / 56px) and a drawer below `lg`. It has a workspace switcher, an API/environment indicator, and a pathname breadcrumb. The topbar holds search (Ctrl K and `/`), the period scope, system status, notifications, shortcuts, theme and density, and the user menu.
- **Navigation** (`components/navigation/nav-config.ts`): 12 IA items in 4 groups. Items not built yet appear as disabled *Planned* rows, never as dead links.
- **Primitives** (`components/ui/`): button, badge, overlay (popover, sheet, tooltip), controls, states (skeleton, empty, error, freshness, toast).
- **Data** (`components/data/`):
  - DataTable: multi-sort, facets, column visibility, global density, sticky header and first column, selection with bulk actions, expansion, keyboard rows, CSV export, and all five states.
  - Cards and a provenance sheet.
  - SVG charts: sparkline, area, paired bars, waterfall. Each chart ships a hidden data table.
- **Pages**:
  - `/app`: Control Center.
  - `/app/alerts`: alerts table and detail sheet.
  - `/app/workflows`: my tasks, approvals, reconciliation.
  - `/app/portfolio`, `/app/investments`, `/app/deals`: legacy blocks wrapped in the new shell, with a banner.

## Data sources

| Surface | Source |
| --- | --- |
| Workspaces | `GET /api/v1/me/access`, falling back to a labelled demo list when the API is unreachable |
| KPIs, queues, alerts, workflows, charts | `web/lib/demo-data.ts`. Every page shows a **Demo data** freshness badge. |

## Known gaps

- `web/` has no test runner and no ESLint install. Verification was `tsc --noEmit`, `next build`, and manual browser checks in light, dark and mobile.
- Decisions (approve, resolve, reject) are kept in component state only, and the toast says so. They need workflow API endpoints.
- Phase 4 (object pages: fund, company, investment) and phase 5 (deal pipeline on the prospects API) replace the legacy wrappers.
