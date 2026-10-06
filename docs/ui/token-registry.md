# OCTO token registry (Vestra-calibrated)

This is the source of truth behind the `.octo-app` tokens in `web/app/globals.css`.

It closes the "required follow-up" items in the Vestra crawl appendix of the mega plan. That appendix could not reach the Vestra DOM. On **2026-09-30** these values were read from the live deployment (`vestra-dashboard01.vercel.app`) through the browser: stylesheet custom properties and `getComputedStyle` at 1440×900, in light and dark.

Legend for the tables below:

- **V (verified):** read from Vestra's CSS or computed styles.
- **O (OCTO):** our value. It differs from Vestra only where the mega plan or WCAG AA requires it.

## Colour

| Semantic | OCTO token | Light V | Light O | Dark V | Dark O |
| --- | --- | --- | --- | --- | --- |
| Canvas (L0) | `app` | `--vestra-bg #fff` | `#ffffff` | `#121212` | `#121212` |
| Chrome / sidebar (L1) | `subtle` | `--vestra-sidebar #f8f8f8` | `#f8f8f8` | `#191919` | `#191919` |
| Card (L2) | `surface` | `--vestra-card #fff` | `#ffffff` | `#141414` | `#141414` |
| Popover (L3) | `raised` | `--popover #fff` | `#ffffff` | `--vestra-pop #1c1c1c` | `#1c1c1c` |
| Recessed | `muted` | `--vestra-muted-bg #f1f5f9` | `#f1f5f9` | `#1e1e1e` | `#1e1e1e` |
| Hover | `hover` | `--vestra-hover #f1f5f9` | `#f1f5f9` | `#ffffff0d` | `rgb(255 255 255 / .05)` |
| Table head | `head` | `--vestra-head #f7f7f8` | `#f7f7f8` | `#101010` | `#101010` |
| Card border | `line` | `--vestra-card-border #18181b0f` | `rgb(24 24 27 / .07)` | `#ffffff05` | `rgb(255 255 255 / .07)` ¹ |
| Control border | `line-strong` | `--vestra-control-border #18181b0d` | `rgb(24 24 27 / .13)` ¹ | `#ffffff0d` | `rgb(255 255 255 / .13)` |
| Text primary | `ink` | `--vestra-text #020617` | `#020617` | `#fafafa` | `#fafafa` |
| Text secondary | `ink-2` | `--vestra-secondary #475569` | `#334155` ⁵ | `#a1a1aa` | `#d4d4d8` ² |
| Text tertiary | `ink-3` | `--vestra-muted #64748b` | `#526072` ⁵ | `#a1a1aa` | `#a1a1aa` |
| Text faint | `ink-4` | `--vestra-faint #94a3b8` | `#667085` ⁵ | `#71717a` | `#8b8b94` ⁵ |
| Primary (text, border, ring) | `accent` | `--vestra-cta #2563eb` | `#2563eb` | `#3b82f6` | `#3b82f6` |
| Primary fill (under white text) | `accent-fill` | `#2563eb` | `#2563eb` | `#3b82f6` | `#2563eb` ⁶ |
| Text on accent tint | `accent-ink` | `--vestra-nav-fg #2563eb` | `#1d4ed8` ⁵ | `#fafafa` | `#93c5fd` |
| Primary hover (fills) | `accent-hover` | `#1d4ed8` | `#1d4ed8` | `#2563eb` | `#1d4ed8` |
| Selected nav | `accent-soft` | `--vestra-nav-active #eff6ff` | `#eff6ff` | `#ffffff14` | `rgb(59 130 246 / .14)` ³ |
| Gain (marks) | `gain` | `#00B36A` (inline) | `#00b36a` | same | same |
| Loss (marks) | `loss` | `#F14D6E` / `#FD4438` (inline) | `#f14d6e` | same | same |
| Positive text | `ok` | `#00B36A` | `#047857` ⁴ | — | `#34d399` |
| Warning text | `warn` | — | `#92400e` ⁴ | — | `#fbbf24` |
| Negative text | `danger` | `--destructive #ef4444` | `#b91c1c` ⁴ | `#ef4444` | `#f87171` |
| Info text | `info` | — | `#1d4ed8` ⁴ | — | `#60a5fa` |
| AI / generated | `ai` | — | `#7c3aed` | — | `#a78bfa` |

Notes:

1. Vestra's hairlines are 4–6% alpha. OCTO keeps that weight for cards but doubles it for controls, so inputs stay findable.
2. In dark mode Vestra uses the same grey for secondary and tertiary text. OCTO splits them to keep the hierarchy.
3. A hover that gets lighter reads better on dark surfaces. A tinted blue selection keeps the "selected = accent" rule in both themes.
4. `#00B36A` on white is about 3:1, and `#ef4444` is about 3.8:1. Both fail AA for small text. The bright values stay for sparklines and bars, where the 3:1 non-text rule applies. Status text is also checked on its own 10% tint, which is how badges render.
5. The axe gate (`e2e/a11y.spec.ts`, 17 routes in light and dark) failed Vestra's slate text steps at 11–12px on tinted surfaces (#64748b measured 4.34:1 on `#f1f5f9`; #94a3b8 measured 2.4:1). OCTO darkens each step, keeping the hierarchy, so every text token clears 4.5:1 on white, `subtle`, `muted` and `head`.
6. White on Vestra's dark CTA `#3b82f6` measures 3.67:1. OCTO fills with `#2563eb` (5.2:1) and keeps `#3b82f6` for text and borders, where it clears 4.9:1 on the dark surface.

## Typography (V = computed)

The font is Inter throughout, with tight tracking and tabular figures on numbers. OCTO drops the monospace font for KPI values and keeps it only for IDs.

| Role | Vestra (V) | OCTO token |
| --- | --- | --- |
| KPI value (card) | 28px / 600 / −0.7px | `text-kpi` 26px / 600 |
| Chart headline value | 36px / 700 / −1.08px, tabular | `text-kpi-xl` 32px / 650 |
| Panel title | 16px / 500 / −0.32px | `text-section` 16px / 600 |
| KPI label | 12px / 500 / −0.3px | 12px / 500 |
| Nav item | 14px / 500 | 13–14px / 500 |
| Nav section label | 12px / 500 | `text-label` 11px caps |
| Table header | 10px / 600 / −0.1px | 11px / 600 caps ⁵ |
| Table cell number | 12px / 500 tabular | 13px tabular |
| Table entity name | 13px / 600 | 13px / 600 |
| Entity sub-meta | 10px faint | 11–12px `ink-3` ⁵ |
| Breadcrumb | 14px / 500, `ink-3` | 13px |
| News tag | 7px / 600 caps | 10px / 600 caps ⁵ |

5. Vestra's 7–10px text is below what OCTO accepts for legibility on data surfaces, so OCTO rounds it up.

## Geometry (V = computed)

| Element | Vestra | OCTO |
| --- | --- | --- |
| Sidebar width | 280px, padding 20px, hidden < 1024px | 248px expanded / 56px collapsed, drawer < 1024px |
| Header | 67px tall, `px-28`, `py-14`, bottom hairline, sticky | 56px, same structure |
| Main padding | `16px 28px 48px` | `20px 28px 48px` (16px on mobile) |
| KPI card | 152px tall, padding 16px, radius 12px, 1px hairline, no shadow | same anatomy, radius `xl` 12px |
| Large panel | radius 16px, padding 16–36px | radius 12px (the plan forbids 16px+ in dense UI) |
| Card gap | 12px between cards, 16px between rows | `gap-3` / `gap-4` |
| Nav item | 32–33px, padding `8px 10px`, radius 12px, active = `nav-active` bg + 1px border + blue text | 32px, radius 8px, same active treatment |
| Controls | 36px, padding `0 14px`, radius 9–10px, `--vestra-input #fcfcfc` fill | sm 28 / md 32 / lg 36px, radius `lg` 10px |
| Primary CTA | blue fill, 1px `#ffffff2e` border, inset top highlight | same, including the inset highlight |
| Filter tab | 32px, radius 10px, 13px / 500, active = blue text | `Tabs` pill variant |
| Table head | 40px, `head` bg, radius 8px | 40px sticky, `head` bg |
| Table row | 44px (simple) / 62px (two-line entity), radius 8px, hover `hover` | compact 44px / comfortable 52px |
| Row checkbox hit area | 28×28, radius 6px | 28×28 target, 16px box |
| Tag | 2px 6px, radius 4px | radius `xs` 4px |
| Avatar | 33px, radius 8px | 28px, radius 8px |

## Charts (V)

- The charts are hand-rolled SVG; there is no Recharts or ECharts in the bundle. That supports OCTO's dependency-free SVG chart system.
- The line is a single 1.5–2px primary stroke with an area fill as a vertical gradient to transparent. Gridlines are horizontal only and very light, and ticks are 12px / 500.
- On hover:
  - a full-height vertical cursor line and a point marker appear;
  - a compact tooltip shows the label and value;
  - the panel's headline value updates to the hovered point;
  - the series after the cursor is dimmed.
- Sparklines are green (`#00B36A`) or red (`#F70007` / `#FD4438`), 1.5px, with a gradient fill.
- Allocation is a thick-ring donut with rounded segment ends and a legend below.

## Motion (V)

- Controls transition `background-color` and `transform` over 150ms with `cubic-bezier(0.4, 0, 0.2, 1)`.
- Pages fade content in on route entry.
- OCTO tokens: `--duration-fast` 120ms, `--duration-base` 180ms, `--duration-slow` 240ms, `--ease-standard`. Everything collapses to 1ms under `prefers-reduced-motion`.

## Crawl checklist status

- [x] Global canvas, surface, text, border, status and accent tokens (light and dark)
- [x] Typography family, weight, size, line-height and tracking
- [x] Spacing and gap scale (cards 12/16px, page gutters 28px, control padding 14px)
- [x] Radius family (4 / 8 / 9–10 / 12 / 16px observed; OCTO caps at 12px)
- [x] Normal and hover states for controls. Focus uses `focus-visible:border-blue-500`, and OCTO keeps its 2px outline ring.
- [x] Sidebar and topbar dimensions and responsive behaviour (`hidden lg:flex`)
- [x] KPI card anatomy and financial-number hierarchy
- [x] Table density, alignment, selection, status pills, sparkline column
- [x] Chart grid, axis, tooltip, cursor and area-fill interaction
- [x] Allocation donut encoding
- [x] Settings sections: Profile, Appearance (dark mode), Alerts (switches), Shortcuts
- [x] Dark-theme relationships (layered near-blacks, alpha hairlines)
- [ ] Empty, error and no-permission states: Vestra shows none, so OCTO's own `feedback/` states are the reference
- [x] Motion timing; reduced motion is enforced by OCTO
- [x] Responsive: the sidebar hides below 1024px and gutters step 16 → 24 → 28px
