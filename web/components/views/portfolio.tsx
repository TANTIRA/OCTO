"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { AlertOctagon, ArrowDownRight, ArrowUpRight, Boxes, GitCompareArrows, Hourglass, Landmark, Newspaper, PieChart, Scale, ShieldCheck, Target, TrendingUp } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { useInvestments } from "@/lib/data/queries";
import {
  ALLOCATION,
  AS_OF,
  DEMO_NOW,
  EXCEPTIONS,
  EXPOSURE_CHANGE,
  FUNDS,
  NAV_SERIES,
  PORTFOLIO,
  PORTFOLIO_METRICS,
  RECON,
  APPROVALS,
  SECONDARY_METRICS,
  SIGNALS,
  companyById,
  fundById,
  fundDpi,
  fundNavSeries,
  fundTvpi,
  hrefFor,
  investmentMoic,
  type Fund,
  type Investment,
  type Metric,
} from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { Panel, PanelBody, PanelHead } from "@/components/page/panel";
import { MetricCard, MetricGrid, useMetricValue } from "@/components/metric/metric-card";
import { LineageDrawer } from "@/components/metric/metric-lineage";
import { KpiMetricDrawer } from "@/components/metric/kpi-drawer";
import { ChartShell } from "@/components/chart/chart-shell";
import { NavChart } from "@/components/chart/nav-chart";
import { RankingBars } from "@/components/chart/bar-chart";
import { DonutChart } from "@/components/chart/donut-chart";
import { DataTable, type Column } from "@/components/data/data-table";
import { DeltaCell, EntityCell, NumericCell, SparklineCell, StatusCell } from "@/components/data/cells";
import { LinkButton } from "@/components/ui/button";
import { Monogram, Tag, type Tone } from "@/components/ui/badge";
import { Segmented, Select, Tabs } from "@/components/ui/controls";
import { FreshnessBadge } from "@/components/feedback";
import { useBreadcrumb } from "@/components/shell/shell-context";

const now = new Date(DEMO_NOW);
type Dim = keyof typeof ALLOCATION;
/** The Investments filter (?<param>=value) each allocation dimension drills into. */
const INVESTMENT_FILTER: Record<Dim, string> = { sector: "sector", geography: "geo", strategy: "strategy", fund: "fund", currency: "currency" };
const DIMS: { value: Dim; label: string }[] = [
  { value: "sector", label: "Sector" },
  { value: "geography", label: "Geography" },
  { value: "strategy", label: "Strategy" },
  { value: "currency", label: "Currency" },
];

const RISK_TONE: Record<Investment["riskStatus"], Tone> = { "On track": "ok", Watch: "warn", "At risk": "danger" };
const FUND_TONE: Record<Fund["status"], Tone> = { Investing: "ok", Harvesting: "info", Watch: "warn", Exiting: "neutral", Fundraising: "accent" };

/**
 * Portfolio overview (plan §10, §11, §38 `/app/portfolio`): "How is the
 * portfolio performing, and what needs attention?" KPI strip, value trend
 * with fund/benchmark/period controls, attention modules, allocation,
 * movers, the holdings grid, fund performance and intelligence.
 */
export function PortfolioView() {
  const f = useFormat();
  const router = useRouter();
  const fmt = useMetricValue();
  useBreadcrumb(null);
  const inv = useInvestments();
  const rows = inv.data ?? [];

  const [lineage, setLineage] = useState<Metric | null>(null);
  const [kpi, setKpi] = useState<Metric | null>(null);
  const [fundScope, setFundScope] = useState("all");
  const [dim, setDim] = useState<Dim>("sector");
  const [sectorTab, setSectorTab] = useState("All");

  const nav = PORTFOLIO_METRICS[0];
  const byFv = [...rows].sort((a, b) => b.fairValue - a.fairValue);
  const totalFv = rows.reduce((n, i) => n + i.fairValue, 0) || 1;
  const largest = byFv[0];
  const top5 = byFv.slice(0, 5).reduce((n, i) => n + i.fairValue, 0) / totalFv;

  const strip: { m: Metric; icon: React.ReactNode }[] = [
    { m: nav, icon: <Landmark /> },
    { m: { id: "positions", label: "Positions", value: rows.length, format: "count", comparison: `${new Set(rows.map((r) => r.companyId)).size} companies · ${FUNDS.length} funds`, provenance: { ...nav.provenance, formula: "Count of open positions", inputs: FUNDS.map((x) => ({ label: x.short, value: String(rows.filter((r) => r.fundId === x.id).length) })) } }, icon: <Boxes /> },
    {
      m: { id: "largest", label: "Largest exposure", value: largest ? (largest.fairValue / totalFv) * 100 : 0, format: "pct", comparison: largest ? companyById(largest.companyId)!.name : "—", provenance: { ...nav.provenance, formula: "max(position FV) ÷ Σ position FV", inputs: largest ? [{ label: companyById(largest.companyId)!.name, value: f.money(largest.fairValue) }, { label: "All positions", value: f.money(totalFv) }] : [] } },
      icon: <Target />,
    },
    { m: { id: "top5", label: "Top-5 concentration", value: top5 * 100, format: "pct", delta: 0.6, deltaUnit: "pts", upIsGood: false, comparison: "vs Q2 2026", provenance: { ...nav.provenance, formula: "Σ top-5 position FV ÷ Σ position FV", inputs: byFv.slice(0, 5).map((i) => ({ label: companyById(i.companyId)!.name, value: f.money(i.fairValue) })) } }, icon: <Scale /> },
    { m: { id: "moic", label: "Gross MOIC", value: SECONDARY_METRICS.moic, format: "multiple", delta: 0.04, deltaUnit: "×", upIsGood: true, comparison: "vs Q2 2026", provenance: { ...nav.provenance, formula: "(Σ FV + Σ realised) ÷ Σ cost", inputs: [{ label: "Fair value", value: f.money(SECONDARY_METRICS.unrealized) }, { label: "Cost", value: f.money(PORTFOLIO.invested) }] } }, icon: <TrendingUp /> },
    { m: { id: "girr", label: "Gross IRR", value: SECONDARY_METRICS.grossIrr, format: "pct", delta: -0.3, deltaUnit: "pts", upIsGood: true, comparison: "vs Q2 2026", provenance: { ...nav.provenance, formula: "XIRR(gross deal cash flows, FV)", inputs: [{ label: "Deal flows", value: "312 flows" }] } }, icon: <PieChart /> },
  ];

  // Value trend: portfolio or a single fund, with a benchmark and range.
  const scope = fundScope === "all" ? null : fundById(fundScope)!;
  const base = useMemo(() => (scope ? fundNavSeries(scope).map((p, i) => ({ q: p.q, nav: p.nav, benchmark: Math.round(fundNavSeries(scope)[0].nav * Math.pow(1.021, i) * 10) / 10 })) : NAV_SERIES), [scope]);

  const attention = [
    { title: "Covenant issues", icon: <AlertOctagon />, tone: "text-danger", items: EXCEPTIONS.filter((e) => e.kind === "Covenant"), href: "/app/alerts" },
    { title: "Valuations stale", icon: <Hourglass />, tone: "text-warn", items: EXCEPTIONS.filter((e) => e.kind === "Valuation stale"), href: "/app/investments" },
    { title: "Recon breaks", icon: <GitCompareArrows />, tone: "text-warn", items: RECON.filter((r) => r.state !== "Resolved").map((r) => ({ id: r.id, title: `${r.field} · ${r.variance}`, entity: r.entity, href: "/app/reconciliation" })), href: "/app/reconciliation" },
    { title: "Approval items", icon: <ShieldCheck />, tone: "text-accent", items: APPROVALS.map((a) => ({ id: a.id, title: a.title, entity: a.entity, href: `/app/workflows?tab=approvals&id=${a.id}` })), href: "/app/workflows?tab=approvals" },
  ];

  const movers = [...rows].sort((a, b) => b.qtdChange - a.qtdChange);
  const gainers = movers.filter((m) => m.qtdChange > 0).slice(0, 4);
  const losers = [...movers].reverse().filter((m) => m.qtdChange < 0).slice(0, 4);

  const sectors = ["All", ...new Set(rows.map((r) => companyById(r.companyId)!.sector))];
  const holdings = sectorTab === "All" ? rows : rows.filter((r) => companyById(r.companyId)!.sector === sectorTab);

  const cols: Column<Investment>[] = useMemo(
    () => [
      {
        id: "company",
        header: "Investment",
        width: 260,
        hideable: false,
        value: (i) => companyById(i.companyId)!.name,
        cell: (i) => {
          const c = companyById(i.companyId)!;
          return <EntityCell name={c.name} sub={`${c.sector} · ${c.geography}`} href={`/app/companies/${c.id.toLowerCase()}`} />;
        },
      },
      { id: "fund", header: "Fund", value: (i) => fundById(i.fundId)!.short, facet: true, groupable: true },
      { id: "strategy", header: "Strategy", value: (i) => fundById(i.fundId)!.strategy, facet: true, groupable: true, defaultHidden: true },
      { id: "instrument", header: "Instrument", value: (i) => i.instrument, defaultHidden: true },
      { id: "cost", header: "Cost", value: (i) => i.cost, align: "right", cell: (i) => <NumericCell value={i.cost} muted />, aggregate: (rs) => f.money(rs.reduce((n, r) => n + r.cost, 0)) },
      { id: "fv", header: "Fair value", value: (i) => i.fairValue, align: "right", cell: (i) => <NumericCell value={i.fairValue} />, aggregate: (rs) => f.money(rs.reduce((n, r) => n + r.fairValue, 0)) },
      { id: "own", header: "Ownership", value: (i) => i.ownership, align: "right", cell: (i) => (i.ownership ? <NumericCell value={i.ownership} kind="pct" digits={0} muted /> : <span className="text-ink-4">Debt</span>) },
      { id: "moic", header: "MOIC", value: (i) => investmentMoic(i), align: "right", cell: (i) => <NumericCell value={investmentMoic(i)} kind="multiple" /> },
      { id: "irr", header: "IRR", value: (i) => i.irr, align: "right", cell: (i) => <NumericCell value={i.irr} kind="pct" /> },
      { id: "weight", header: "Weight", value: (i) => (i.fairValue / totalFv) * 100, align: "right", cell: (i) => <NumericCell value={(i.fairValue / totalFv) * 100} kind="pct" muted />, aggregate: (rs) => f.pct((rs.reduce((n, r) => n + r.fairValue, 0) / totalFv) * 100) },
      { id: "qtd", header: "QTD", value: (i) => i.qtdChange, align: "right", cell: (i) => <DeltaCell value={i.qtdChange} /> },
      { id: "trend", kind: "trend", header: "Trend", value: (i) => i.trend[i.trend.length - 1], sortable: false, cell: (i) => <SparklineCell values={i.trend} risk={i.riskStatus} /> },
      { id: "risk", header: "Risk", value: (i) => i.riskStatus, facet: true, cell: (i) => <StatusCell tone={RISK_TONE[i.riskStatus]}>{i.riskStatus}</StatusCell> },
    ],
    [f, totalFv],
  );

  const fundCols: Column<Fund>[] = [
    { id: "name", header: "Fund", width: 240, value: (x) => x.name, cell: (x) => <EntityCell name={x.name} sub={x.geography} href={`/app/funds/${x.slug}`} /> },
    { id: "vintage", header: "Vintage", value: (x) => x.vintage, align: "right" },
    { id: "strategy", header: "Strategy", value: (x) => x.strategy },
    { id: "committed", header: "Committed", value: (x) => x.committed, align: "right", cell: (x) => <NumericCell value={x.committed} muted /> },
    { id: "called", header: "Invested", value: (x) => x.called, align: "right", cell: (x) => <NumericCell value={x.called} muted /> },
    { id: "nav", header: "NAV", value: (x) => x.nav, align: "right", cell: (x) => <NumericCell value={x.nav} /> },
    { id: "tvpi", header: "TVPI", value: (x) => fundTvpi(x), align: "right", cell: (x) => <NumericCell value={fundTvpi(x)} kind="multiple" /> },
    { id: "dpi", header: "DPI", value: (x) => fundDpi(x), align: "right", cell: (x) => <NumericCell value={fundDpi(x)} kind="multiple" /> },
    { id: "irr", header: "Net IRR", value: (x) => x.netIrr, align: "right", cell: (x) => <NumericCell value={x.netIrr} kind="pct" /> },
    { id: "status", header: "Status", value: (x) => x.status, cell: (x) => <StatusCell tone={FUND_TONE[x.status]}>{x.status}</StatusCell> },
  ];

  return (
    <>
      <PageHeader
        variant="dashboard"
        eyebrow="Overview"
        title="Portfolio"
        description="How the portfolio is performing, where it is exposed, and what needs attention."
        meta={<FreshnessBadge state="demo" asOf={`as of ${f.date(AS_OF)}`} />}
        actions={
          <>
            <LinkButton href="/app/analytics">Open analytics</LinkButton>
            <LinkButton href="/app/investments" variant="primary">
              Investment explorer
            </LinkButton>
          </>
        }
      />
      <PageBody className="space-y-6">
        <MetricGrid cols={6}>
          {strip.map(({ m, icon }) => (
            <MetricCard key={m.id} metric={m} icon={icon} onOpen={setKpi} state={inv.isLoading ? "loading" : "ready"} />
          ))}
        </MetricGrid>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
          <NavChart
            className="xl:col-span-8"
            height={360}
            title={scope ? `${scope.name} NAV` : "Portfolio value"}
            points={base}
            state={inv.isLoading ? "loading" : "ready"}
            onLineage={() => setLineage(nav)}
            toolbar={
              <Select aria-label="Fund" value={fundScope} onChange={(e) => setFundScope(e.target.value)} className="w-40 [&_select]:text-[12px]">
                <option value="all">All funds</option>
                {FUNDS.map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.short}
                  </option>
                ))}
              </Select>
            }
          />

          <ChartShell
            className="xl:col-span-4"
            title="Allocation"
            subtitle="Share of position fair value · select a slice to see its positions"
            toolbar={<Segmented size="sm" label="Allocation dimension" value={dim} onChange={setDim} items={DIMS.map((d) => ({ value: d.value, label: d.label.slice(0, 4) === "Geog" ? "Geo" : d.label }))} />}
            exportData={{ filename: `allocation-${dim}`, head: [DIMS.find((d) => d.value === dim)!.label, "Fair value", "Share %"], rows: ALLOCATION[dim].map((s) => [s.key, Math.round(s.value), s.share.toFixed(1)]) }}
            height="auto"
          >
            <DonutChart
              data={ALLOCATION[dim].map((s) => ({ key: s.key, value: s.value }))}
              label={`Allocation by ${dim}`}
              format={(v) => f.money(v)}
              onSelect={(key) => router.push(`/app/investments?${INVESTMENT_FILTER[dim]}=${encodeURIComponent(key)}`)}
            />
          </ChartShell>
        </div>

        <section aria-label="Portfolio attention" className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {attention.map((a) => (
            <Panel key={a.title}>
              <PanelHead
                title={a.title}
                icon={<span className={a.tone}>{a.icon}</span>}
                toolbar={<span className="text-kpi font-semibold tabular-nums text-ink">{a.items.length}</span>}
              />
              <PanelBody className="pt-1">
                <ul className="space-y-2">
                  {a.items.slice(0, 2).map((it) => (
                    <li key={it.id}>
                      <button type="button" onClick={() => router.push(it.href)} className="w-full cursor-pointer rounded-md px-1 py-1 text-left hover:bg-hover focus-visible:outline-2 focus-visible:outline-focus">
                        <p className="truncate text-[12px] font-medium text-ink">{it.title}</p>
                        <p className="truncate text-[11px] text-ink-3">{it.entity.name}</p>
                      </button>
                    </li>
                  ))}
                  {a.items.length === 0 && <li className="text-[12px] text-ink-3">Nothing open.</li>}
                </ul>
                {a.items.length > 2 && (
                  <LinkButton href={a.href} size="xs" variant="link" className="mt-1">
                    +{a.items.length - 2} more
                  </LinkButton>
                )}
              </PanelBody>
            </Panel>
          ))}
        </section>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
          <Panel className="xl:col-span-5">
            <PanelHead title="Top movers" description="Change in fair value this quarter" icon={<TrendingUp />} toolbar={<span className="text-[12px] text-ink-3">QTD</span>} />
            <PanelBody className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {[
                { label: "Gainers", items: gainers, tone: "gain" as const },
                { label: "Losers", items: losers, tone: "loss" as const },
              ].map((g) => (
                <div key={g.label} className={g.tone === "gain" ? "rounded-lg border border-line bg-gain/5 p-3" : "rounded-lg border border-line bg-loss/5 p-3"}>
                  <p className={g.tone === "gain" ? "text-[11px] font-semibold uppercase tracking-[0.06em] text-ok" : "text-[11px] font-semibold uppercase tracking-[0.06em] text-danger"}>{g.label}</p>
                  <ul className="mt-2 space-y-2.5">
                    {g.items.map((i) => {
                      const c = companyById(i.companyId)!;
                      return (
                        <li key={i.id} className="flex items-center gap-2">
                          <Monogram name={c.name} size="sm" />
                          <div className="min-w-0 flex-1">
                            <p className="truncate text-[12px] font-semibold text-ink">{c.name}</p>
                            <p className="truncate text-[11px] text-ink-3">{fundById(i.fundId)!.short}</p>
                          </div>
                          <span className={g.tone === "gain" ? "inline-flex items-center text-[12px] font-semibold tabular-nums text-ok" : "inline-flex items-center text-[12px] font-semibold tabular-nums text-danger"}>
                            {g.tone === "gain" ? <ArrowUpRight aria-hidden className="size-3.5" /> : <ArrowDownRight aria-hidden className="size-3.5" />}
                            {f.delta(i.qtdChange)}
                          </span>
                        </li>
                      );
                    })}
                    {g.items.length === 0 && <li className="text-[12px] text-ink-3">No movers.</li>}
                  </ul>
                </div>
              ))}
            </PanelBody>
          </Panel>

          <Panel className="xl:col-span-4">
            <PanelHead title="Exposure ranking" description={`By ${DIMS.find((d) => d.value === dim)!.label.toLowerCase()} · change vs Q2 in pts`} />
            <PanelBody>
              <RankingBars
                label={`Exposure by ${dim}`}
                format={(v) => f.pct(v)}
                items={ALLOCATION[dim].slice(0, 6).map((s) => ({ label: s.key, value: s.share, sub: dim === "sector" && EXPOSURE_CHANGE[s.key] !== undefined ? `${f.delta(EXPOSURE_CHANGE[s.key], "pts")} vs Q2 · ${f.money(s.value)}` : f.money(s.value) }))}
              />
            </PanelBody>
          </Panel>

          <Panel className="xl:col-span-3">
            <PanelHead title="Intelligence" icon={<Newspaper />} toolbar={<Tag tone="info">Demo</Tag>} />
            <PanelBody className="space-y-2 pt-1">
              {SIGNALS.slice(0, 3).map((s) => (
                <article key={s.id} className="min-w-0 rounded-lg border border-line p-3 transition-colors hover:bg-hover">
                  <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
                    <Tag tone={s.kind === "Macro signal" ? "warn" : s.kind === "Regulatory event" ? "info" : "accent"}>{s.kind}</Tag>
                    <time dateTime={s.at} className="text-[11px] text-ink-4">
                      {f.ago(s.at, now)}
                    </time>
                  </div>
                  <p className="mt-1.5 break-words text-[12px] font-semibold leading-snug text-ink">{s.title}</p>
                  {/* V3 INT-002: names wrap instead of clipping inside the narrow column. */}
                  {s.entity ? (
                    <Link href={hrefFor(s.entity) ?? "#"} className="mt-1 inline-block max-w-full break-words text-[12px] text-ink-2 underline decoration-line-strong underline-offset-2 hover:text-accent-ink">
                      <span className="text-[10px] font-semibold uppercase tracking-[0.05em] text-ink-4">{s.entity.type}</span> {s.entity.name}
                    </Link>
                  ) : (
                    <p className="mt-1 break-words text-[12px] text-ink-3">{s.source}</p>
                  )}
                </article>
              ))}
            </PanelBody>
          </Panel>
        </div>

        <section aria-label="Holdings" className="space-y-2">
          <div className="flex flex-wrap items-end justify-between gap-2">
            <h2 className="text-section font-semibold text-ink">Holdings</h2>
            <Tabs variant="pill" label="Filter holdings by sector" value={sectorTab} onChange={setSectorTab} items={sectors.map((s) => ({ value: s, label: s, count: s === "All" ? rows.length : rows.filter((r) => companyById(r.companyId)!.sector === s).length }))} />
          </div>
          <DataTable
            id="holdings"
            label="Holdings"
            data={holdings}
            status={inv.isLoading ? "loading" : "ready"}
            columns={cols}
            rowId={(i) => i.id}
            demo
            totals
            selectable
            onRowOpen={(i) => router.push(`/app/companies/${i.companyId.toLowerCase()}`)}
            exportName="octo-holdings"
            searchPlaceholder="Search holdings…"
            views={[
              { id: "by-value", name: "Largest first", state: { sort: [{ id: "fv", desc: true }] } },
              { id: "at-risk", name: "At risk & watch", state: { sort: [{ id: "qtd", desc: false }], facets: { risk: ["At risk", "Watch"] } } },
              { id: "by-fund", name: "Grouped by fund", state: { groupBy: "fund", sort: [{ id: "fv", desc: true }] } },
            ]}
            bulkActions={(sel, clear) => (
              <LinkButton size="sm" href={`/app/analytics?compare=${sel.map((s) => s.id).join(",")}`} onClick={clear}>
                Compare in analytics
              </LinkButton>
            )}
            empty={{ title: "No holdings", body: "Positions appear once capital is deployed and booked in the IBOR." }}
          />
        </section>

        <Panel>
          <PanelHead title="Fund performance" description="Net of fees" />
          <DataTable id="pf-funds" chrome="minimal" label="Fund performance" data={FUNDS} columns={fundCols} rowId={(x) => x.id} onRowOpen={(x) => router.push(`/app/funds/${x.slug}`)} empty={{ title: "No funds", body: "" }} />
        </Panel>
      </PageBody>

      <KpiMetricDrawer metric={kpi} onClose={() => setKpi(null)} onLineage={(m) => (setKpi(null), setLineage(m))} primary={{ label: "View analytics", href: "/app/analytics" }} />
      <LineageDrawer open={!!lineage} onClose={() => setLineage(null)} title={lineage?.label ?? ""} value={lineage ? fmt(lineage) : ""} provenance={lineage?.provenance ?? null} />
    </>
  );
}
