"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { ArrowRight, Bookmark, Plus } from "lucide-react";
import { cn } from "@/lib/utils";
import { useFormat } from "@/lib/use-format";
import { ALLOCATION, AS_OF, BRIDGE, EXPOSURE_CHANGE, INVESTMENTS, MONTHS_6, NAV_SERIES, PORTFOLIO_METRICS, RISK_PROFILE, SECTOR_HEAT, companyById, investmentMoic, type Metric } from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { Panel, PanelBody, PanelHead } from "@/components/page/panel";
import { ChartShell } from "@/components/chart/chart-shell";
import { TrendChart } from "@/components/chart/line-chart";
import { NavChart } from "@/components/chart/nav-chart";
import { WaterfallChart } from "@/components/chart/waterfall-chart";
import { RiskReturnChart } from "@/components/chart/risk-return";
import { Heatmap } from "@/components/chart/heatmap";
import { RankingBars } from "@/components/chart/bar-chart";
import { Legend } from "@/components/chart/core";
import { LineageDrawer } from "@/components/metric/metric-lineage";
import { useMetricValue } from "@/components/metric/metric-card";
import { Button, LinkButton, ringInset } from "@/components/ui/button";
import { Sheet } from "@/components/ui/overlay";
import { Segmented, Select } from "@/components/ui/controls";
import { FreshnessBadge, useToast } from "@/components/feedback";
import { useBreadcrumb } from "@/components/shell/shell-context";

const HURDLE = 15;

type MetricKey = "nav" | "irr" | "tvpi" | "dpi" | "moic";

const SERIES: Record<MetricKey, { label: string; values: number[]; format: (v: number, f: ReturnType<typeof useFormat>) => string; benchmark?: number[] }> = {
  nav: { label: "NAV ($M)", values: NAV_SERIES.map((p) => p.nav), benchmark: NAV_SERIES.map((p) => p.benchmark), format: (v) => `$${Math.round(v)}M` },
  irr: { label: "Net IRR (%)", values: [16.1, 16.8, 17.2, 17.9, 18.3, 18.6, 18.9, 19.1, 19.0, 18.8, 18.6, 18.2], benchmark: Array(12).fill(15), format: (v, f) => f.pct(v) },
  tvpi: { label: "TVPI (×)", values: [1.31, 1.35, 1.38, 1.42, 1.46, 1.49, 1.52, 1.54, 1.56, 1.59, 1.62, 1.64], format: (v, f) => f.multiple(v) },
  dpi: { label: "DPI (×)", values: [0.12, 0.14, 0.17, 0.19, 0.22, 0.24, 0.26, 0.29, 0.31, 0.33, 0.36, 0.38], format: (v, f) => f.multiple(v) },
  moic: { label: "Gross MOIC (×)", values: [1.22, 1.24, 1.27, 1.29, 1.31, 1.33, 1.35, 1.36, 1.37, 1.39, 1.4, 1.41], format: (v, f) => f.multiple(v) },
};

const SAVED = [
  { id: "sa1", name: "Q3 value creation by sector", owner: "R. Tan", updated: "2d ago" },
  { id: "sa2", name: "Flagship II vs benchmark", owner: "You", updated: "5d ago" },
  { id: "sa3", name: "Renewables risk/return", owner: "M. Sari", updated: "1w ago" },
];

/**
 * Executive analytics (plan §19): one large main chart with a metric switcher
 * and benchmark, a focused right rail (current value, drivers, saved
 * analyses), then attribution, sector heatmap, risk/return and exposure.
 */
export function AnalyticsView() {
  const f = useFormat();
  const params = useSearchParams();
  const toast = useToast();
  const fmtMetric = useMetricValue();
  useBreadcrumb(null);
  const [metric, setMetric] = useState<MetricKey>("nav");
  const [agg, setAgg] = useState<"quarter" | "year">("quarter");
  const [hover, setHover] = useState<number | null>(null);
  const [lineage, setLineage] = useState<Metric | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const compare = (params.get("compare") ?? "").split(",").filter(Boolean);

  const s = SERIES[metric];
  const x = agg === "quarter" ? NAV_SERIES.map((p) => p.q) : ["2024", "2025", "2026 YTD"];
  const pick = (vals: number[]) => (agg === "quarter" ? vals : [vals[4], vals[8], vals[11]]);
  const values = pick(s.values);
  const bench = s.benchmark ? pick(s.benchmark) : undefined;
  const i = hover ?? values.length - 1;
  const change = values[i] - values[0];

  const metricSelect = (
    <Select aria-label="Metric" value={metric} onChange={(e) => setMetric(e.target.value as MetricKey)} className="w-36 [&_select]:text-[12px]">
      <option value="nav">NAV</option>
      <option value="irr">Net IRR</option>
      <option value="tvpi">TVPI</option>
      <option value="dpi">DPI</option>
      <option value="moic">Gross MOIC</option>
    </Select>
  );

  const [rrFilter, setRrFilter] = useState({ fund: "", sector: "", vintage: "", strategy: "" });
  const rr = RISK_PROFILE.filter((p) => (!rrFilter.fund || p.fund === rrFilter.fund) && (!rrFilter.sector || p.sector === rrFilter.sector) && (!rrFilter.vintage || String(p.vintage) === rrFilter.vintage) && (!rrFilter.strategy || p.strategy === rrFilter.strategy));
  const sel = RISK_PROFILE.find((p) => p.id === selected) ?? null;
  const selInv = sel ? INVESTMENTS.find((i) => i.id === sel.id) : undefined;
  const focus = compare.length ? INVESTMENTS.filter((inv) => compare.includes(inv.id)) : [];

  return (
    <>
      <PageHeader
        variant="dashboard"
        eyebrow="Insight"
        title="Analytics"
        description="Performance, attribution and risk across the portfolio."
        meta={<FreshnessBadge state="demo" asOf={`as of ${f.date(AS_OF)}`} />}
        actions={
          <Button variant="primary" onClick={() => toast({ tone: "ok", title: "Analysis saved", body: `“${s.label} by ${agg}” saved to your analyses (demo).` })}>
            <Bookmark /> Save analysis
          </Button>
        }
      />
      <PageBody className="space-y-6">
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
          {metric === "nav" ? (
            <NavChart className="xl:col-span-9" height={420} toolbar={metricSelect} onLineage={() => setLineage(PORTFOLIO_METRICS[0])} />
          ) : (
          <ChartShell
            className="xl:col-span-9"
            title={s.label}
            subtitle={bench ? "Portfolio vs public benchmark (rebased)" : "Portfolio"}
            headline={
              <div className="flex flex-wrap items-baseline gap-3">
                <span className="text-kpi-xl font-bold tabular-nums text-ink">{s.format(values[i], f)}</span>
                <span className="text-[12px] font-medium text-ink-3">
                  {x[i]} · {f.delta(change, metric === "irr" ? "pts" : "×", metric === "irr" ? 1 : 2)} since {x[0]}
                </span>
              </div>
            }
            toolbar={
              <div className="flex flex-wrap items-center gap-2">
                {metricSelect}
                <Segmented size="sm" label="Aggregation" value={agg} onChange={setAgg} items={[{ value: "quarter", label: "Quarterly" }, { value: "year", label: "Yearly" }]} />
              </div>
            }
            legend={<Legend items={[{ label: "Portfolio", color: "var(--color-chart-1)" }, ...(bench ? [{ label: metric === "irr" ? "Hurdle 15%" : "Benchmark", color: "var(--color-ink-4)", dashed: true }] : [])]} />}
            freshness={<FreshnessBadge state="demo" />}
            source="Metrics engine · IBOR"
            onLineage={() => setLineage(PORTFOLIO_METRICS.find((m) => m.id === (metric === "moic" ? "tvpi" : metric)) ?? PORTFOLIO_METRICS[0])}
            exportData={{ filename: `analytics-${metric}`, head: ["Period", s.label, "Benchmark"], rows: x.map((p, k) => [p, values[k], bench?.[k] ?? ""]) }}
            height={420}
          >
            <TrendChart key={`${metric}-${agg}`} x={x} series={[{ id: "p", label: "Portfolio", values }, ...(bench ? [{ id: "b", label: metric === "irr" ? "Hurdle" : "Benchmark", values: bench, color: "var(--color-ink-4)", dashed: true, area: false }] : [])]} format={(v) => s.format(v, f)} label={s.label} onHover={setHover} />
          </ChartShell>
          )}

          <aside className="grid grid-cols-1 content-start gap-4 xl:col-span-3" aria-label="Analysis rail">
            <Panel>
              <PanelHead title="Drivers this quarter" />
              <PanelBody className="pt-1">
                <RankingBars label="NAV drivers" format={(v) => f.delta(v * 1e6, "$")} items={BRIDGE.filter((b) => b.kind === "step").map((b) => ({ label: b.label, value: b.value, color: b.value >= 0 ? "var(--color-gain)" : "var(--color-loss)" }))} />
              </PanelBody>
            </Panel>
            <Panel>
              <PanelHead title="Saved analyses" toolbar={<Button size="xs" variant="ghost" onClick={() => toast({ tone: "info", title: "Start from the chart", body: "Pick a metric, then Save analysis." })}><Plus /> New</Button>} />
              <PanelBody flush>
                <ul className="divide-y divide-line">
                  {SAVED.map((a) => (
                    <li key={a.id}>
                      <button type="button" onClick={() => setMetric(a.id === "sa2" ? "nav" : a.id === "sa3" ? "irr" : "tvpi")} className={cn("w-full cursor-pointer px-4 py-2.5 text-left hover:bg-hover", ringInset)}>
                        <p className="text-[13px] font-medium text-ink">{a.name}</p>
                        <p className="text-[11px] text-ink-3">
                          {a.owner} · {a.updated}
                        </p>
                      </button>
                    </li>
                  ))}
                </ul>
              </PanelBody>
            </Panel>
          </aside>
        </div>

        {focus.length > 0 && (
          <Panel>
            <PanelHead title="Comparison" description={`${focus.length} positions selected from holdings`} />
            <PanelBody className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {focus.map((inv) => (
                <div key={inv.id} className="rounded-lg border border-line p-3">
                  <p className="truncate text-[13px] font-semibold text-ink">{companyById(inv.companyId)!.name}</p>
                  <dl className="mt-2 grid grid-cols-3 gap-2 text-[12px]">
                    <div><dt className="text-ink-4">FV</dt><dd className="font-semibold tabular-nums">{f.money(inv.fairValue)}</dd></div>
                    <div><dt className="text-ink-4">MOIC</dt><dd className="font-semibold tabular-nums">{f.multiple(investmentMoic(inv))}</dd></div>
                    <div><dt className="text-ink-4">IRR</dt><dd className="font-semibold tabular-nums">{f.pct(inv.irr)}</dd></div>
                  </dl>
                </div>
              ))}
            </PanelBody>
          </Panel>
        )}

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
          <ChartShell
            className="xl:col-span-5"
            title="Value creation bridge"
            subtitle="Q3: opening $787.0M + $42.0M calls − $31.8M distributions + $17.4M valuation − $2.2M FX = closing $812.4M"
            legend={<Legend items={[{ label: "Opening / closing", color: "var(--color-mark-neutral)" }, { label: "Increase", color: "var(--color-gain)" }, { label: "Decrease", color: "var(--color-loss)" }]} />}
            height={420}
            expandable={false}
            exportData={{ filename: "bridge", head: ["Step", "$M"], rows: BRIDGE.map((b) => [b.label, b.value]) }}
          >
            <WaterfallChart data={BRIDGE} label="Q3 value creation bridge" format={(v) => `${v < 0 ? "−" : ""}$${Math.abs(v).toFixed(1)}M`} axisFormat={(v) => `$${Math.round(v)}M`} />
          </ChartShell>
          <ChartShell
            className="xl:col-span-7"
            title="Risk vs return"
            subtitle={`${rr.length} of ${RISK_PROFILE.length} positions · gross IRR vs volatility of marks (demo) · dashed lines: median risk and ${HURDLE}% hurdle`}
            toolbar={
              <>
                {(
                  [
                    ["fund", "Fund", "funds", [...new Set(RISK_PROFILE.map((p) => p.fund))]],
                    ["sector", "Sector", "sectors", [...new Set(RISK_PROFILE.map((p) => p.sector))].sort()],
                    ["vintage", "Vintage", "vintages", [...new Set(RISK_PROFILE.map((p) => String(p.vintage)))].sort()],
                    ["strategy", "Strategy", "strategies", [...new Set(RISK_PROFILE.map((p) => p.strategy))]],
                  ] as const
                ).map(([key, name, plural, opts]) => (
                  <Select key={key} aria-label={name} value={rrFilter[key]} onChange={(e) => setRrFilter((s) => ({ ...s, [key]: e.target.value }))} className="w-32 [&_select]:text-[12px]">
                    <option value="">All {plural}</option>
                    {opts.map((o) => (
                      <option key={o} value={o}>
                        {o}
                      </option>
                    ))}
                  </Select>
                ))}
              </>
            }
            height={420}
            exportData={{ filename: "risk-return", head: ["Company", "Fund", "Risk %", "Return %", "Fair value"], rows: rr.map((p) => [p.company, p.fund, p.risk, p.ret, Math.round(p.fv)]) }}
          >
            {({ expanded }) => <RiskReturnChart key={String(expanded)} points={rr} hurdle={HURDLE} money={(v) => f.money(v)} label="Risk vs return by position" onSelect={(p) => setSelected(p.id)} />}
          </ChartShell>
        </div>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
          <Panel className="xl:col-span-7">
            <PanelHead title="Sector valuation change" description="Month-on-month change in fair value by sector, %" />
            <PanelBody>
              <Heatmap rows={SECTOR_HEAT.map((r) => ({ label: r.sector, values: r.values }))} columns={MONTHS_6} format={(v) => `${v > 0 ? "+" : v < 0 ? "−" : ""}${Math.abs(v).toFixed(1)}`} label="Sector valuation change by month" />
            </PanelBody>
          </Panel>
          <Panel className="xl:col-span-5">
            <PanelHead title="Sector exposure and change" description="Weight, with change vs Q2 in pts" />
            <PanelBody>
              <RankingBars label="Sector exposure" format={(v) => f.pct(v)} items={ALLOCATION.sector.map((a) => ({ label: a.key, value: a.share, sub: EXPOSURE_CHANGE[a.key] !== undefined ? `${f.delta(EXPOSURE_CHANGE[a.key], "pts")} vs Q2` : undefined }))} />
            </PanelBody>
          </Panel>
        </div>
      </PageBody>

      <Sheet open={!!sel} onClose={() => setSelected(null)} eyebrow={sel ? `Position · ${sel.id}` : ""} title={sel?.company ?? ""}
        footer={
          sel && (
            <div className="flex flex-wrap justify-end gap-2">
              <LinkButton href={`/app/investments?focus=${sel.id}`}>Open position</LinkButton>
              <LinkButton variant="primary" href={`/app/companies/${sel.companyId.toLowerCase()}`}>
                Open company <ArrowRight />
              </LinkButton>
            </div>
          )
        }
      >
        {sel && (
          <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-[13px]">
            {[
              ["Fund", sel.fund],
              ["Sector", sel.sector],
              ["Instrument", sel.instrument],
              ["Vintage", String(sel.vintage)],
              ["Return (gross IRR)", f.pct(sel.ret)],
              ["Risk (volatility, demo)", `${sel.risk.toFixed(1)}%`],
              ["Fair value", f.money(sel.fv)],
              ["MOIC", selInv ? f.multiple(investmentMoic(selInv)) : "—"],
              ["Quadrant", `${sel.risk <= [...RISK_PROFILE].sort((a, b) => a.risk - b.risk)[Math.floor(RISK_PROFILE.length / 2)].risk ? "Low" : "High"} risk · ${sel.ret >= HURDLE ? "high" : "low"} return`],
              ["Mark status", selInv?.riskStatus ?? "—"],
            ].map(([k, v]) => (
              <div key={k}>
                <dt className="text-[12px] text-ink-3">{k}</dt>
                <dd className="mt-0.5 font-medium tabular-nums text-ink">{v}</dd>
              </div>
            ))}
          </dl>
        )}
      </Sheet>
      <LineageDrawer open={!!lineage} onClose={() => setLineage(null)} title={lineage?.label ?? ""} value={lineage ? fmtMetric(lineage) : ""} provenance={lineage?.provenance ?? null} />
    </>
  );
}
