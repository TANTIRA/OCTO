"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Layers } from "lucide-react";
import { cn } from "@/lib/utils";
import { useFormat } from "@/lib/use-format";
import { useFunds } from "@/lib/data/queries";
import { AS_OF, VINTAGE, fundDpi, fundDryPowder, fundTvpi, investmentsForFund, vintageHistory, type Fund, type Granularity } from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { Panel, PanelHead } from "@/components/page/panel";
import { ChartShell } from "@/components/chart/chart-shell";
import { BarChart } from "@/components/chart/bar-chart";
import { Legend, SERIES } from "@/components/chart/core";
import { TrendChart } from "@/components/chart/line-chart";
import { DataTable, type Column } from "@/components/data/data-table";
import { EntityCell, NumericCell, StatusCell } from "@/components/data/cells";
import { StatusBadge, type Tone } from "@/components/ui/badge";
import { ring } from "@/components/ui/button";
import { Segmented, Select } from "@/components/ui/controls";
import { FreshnessBadge, MetricSkeleton } from "@/components/feedback";
import { useBreadcrumb } from "@/components/shell/shell-context";

type Period = "latest" | Granularity;
const PERIODS: { value: Period; label: string; window: string }[] = [
  { value: "latest", label: "Latest", window: "" },
  { value: "daily", label: "Daily", window: "last 30 days" },
  { value: "weekly", label: "Weekly", window: "last 12 weeks" },
  { value: "monthly", label: "Monthly", window: "last 12 months" },
  { value: "quarterly", label: "Quarterly", window: "last 8 quarters" },
  { value: "yearly", label: "Yearly", window: "since 2019" },
];

export const FUND_TONE: Record<Fund["status"], Tone> = { Investing: "ok", Harvesting: "info", Watch: "warn", Exiting: "neutral", Fundraising: "accent" };

/** Funds (plan §12, §38 `/app/funds`): fund summary cards, comparison table, vintage comparison. */
export function FundsView() {
  const f = useFormat();
  const router = useRouter();
  useBreadcrumb(null);
  const funds = useFunds();
  const [measure, setMeasure] = useState<"irr" | "tvpi">("irr");
  const [period, setPeriod] = useState<Period>("latest");
  const measureLabel = measure === "irr" ? "Net IRR" : "TVPI";
  const fmt = (v: number) => (measure === "irr" ? f.pct(v) : f.multiple(v));
  const history = useMemo(() => vintageHistory(period === "latest" ? "quarterly" : period), [period]);
  // V3 VINTAGE-002: always chronological by numeric year, never by source order.
  const vintage = [...VINTAGE].sort((a, b) => a.vintage - b.vintage);

  const cols: Column<Fund>[] = [
    { id: "name", header: "Fund", width: 250, hideable: false, value: (x) => x.name, cell: (x) => <EntityCell name={x.name} sub={`${x.geography} · ${x.manager}`} href={`/app/funds/${x.slug}`} /> },
    { id: "vintage", header: "Vintage", value: (x) => x.vintage, align: "right", groupable: true },
    { id: "strategy", header: "Strategy", value: (x) => x.strategy, facet: true, groupable: true },
    { id: "committed", header: "Committed", value: (x) => x.committed, align: "right", cell: (x) => <NumericCell value={x.committed} muted />, aggregate: (r) => f.money(r.reduce((n, x) => n + x.committed, 0)) },
    { id: "called", header: "Called", value: (x) => x.called, align: "right", cell: (x) => <NumericCell value={x.called} muted />, aggregate: (r) => f.money(r.reduce((n, x) => n + x.called, 0)) },
    { id: "dry", header: "Dry powder", value: (x) => fundDryPowder(x), align: "right", cell: (x) => <NumericCell value={fundDryPowder(x)} muted />, aggregate: (r) => f.money(r.reduce((n, x) => n + fundDryPowder(x), 0)) },
    { id: "nav", header: "NAV", value: (x) => x.nav, align: "right", cell: (x) => <NumericCell value={x.nav} />, aggregate: (r) => f.money(r.reduce((n, x) => n + x.nav, 0)) },
    { id: "dist", header: "Distributions", value: (x) => x.distributions, align: "right", cell: (x) => <NumericCell value={x.distributions} muted />, defaultHidden: true },
    { id: "tvpi", header: "TVPI", value: (x) => fundTvpi(x), align: "right", cell: (x) => <NumericCell value={fundTvpi(x)} kind="multiple" /> },
    { id: "dpi", header: "DPI", value: (x) => fundDpi(x), align: "right", cell: (x) => <NumericCell value={fundDpi(x)} kind="multiple" /> },
    { id: "irr", header: "Net IRR", value: (x) => x.netIrr, align: "right", cell: (x) => <NumericCell value={x.netIrr} kind="pct" /> },
    { id: "status", header: "Status", value: (x) => x.status, facet: true, cell: (x) => <StatusCell tone={FUND_TONE[x.status]}>{x.status}</StatusCell> },
  ];

  return (
    <>
      <PageHeader variant="list" eyebrow="Invest" title="Funds" description="Commitments, deployment and performance for every vehicle." meta={<FreshnessBadge state="demo" asOf={`as of ${f.date(AS_OF)}`} />} />
      <PageBody className="space-y-6">
        {/* Columns follow the space the page actually has (a container query, not the
            viewport): three with the sidebar open, five only when each card keeps ~16rem. */}
        <section aria-label="Fund summaries" className="@container">
          <div className="grid grid-cols-1 gap-3 @md:grid-cols-2 @3xl:grid-cols-3 @6xl:grid-cols-5">
          {funds.isLoading
            ? Array.from({ length: 5 }, (_, i) => <MetricSkeleton key={i} />)
            : (funds.data ?? []).map((x) => {
                const called = x.called / x.committed;
                return (
                  <Link key={x.id} href={`/app/funds/${x.slug}`} aria-label={`${x.name}: NAV ${f.money(x.nav)}, ${x.status}`} className={cn("group flex flex-col gap-3 rounded-lg border border-line bg-surface p-4 transition-colors hover:border-line-strong hover:bg-hover/40", ring)}>
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="truncate text-[14px] font-semibold text-ink group-hover:text-accent" title={x.name}>
                          {x.short}
                        </p>
                        <p className="truncate text-[12px] text-ink-3">
                          {x.strategy} · {x.vintage}
                        </p>
                      </div>
                      <StatusBadge tone={FUND_TONE[x.status]}>{x.status}</StatusBadge>
                    </div>
                    <div>
                      <p className="text-kpi font-semibold tabular-nums text-ink">{f.money(x.nav)}</p>
                      <p className="text-[12px] text-ink-3">NAV · {investmentsForFund(x.id).length} positions</p>
                    </div>
                    <dl className="grid grid-cols-3 gap-2 text-[12px]">
                      {[
                        ["TVPI", f.multiple(fundTvpi(x))],
                        ["DPI", f.multiple(fundDpi(x))],
                        ["Net IRR", f.pct(x.netIrr)],
                      ].map(([k, v]) => (
                        <div key={k} className="min-w-0">
                          <dt className="truncate text-ink-3">{k}</dt>
                          <dd className="font-semibold tabular-nums text-ink">{v}</dd>
                        </div>
                      ))}
                    </dl>
                    <div className="mt-auto">
                      <div className="h-1.5 overflow-hidden rounded-full bg-muted" role="img" aria-label={`${f.pct(called * 100, 0)} of ${f.money(x.committed)} commitments called`}>
                        <div className="h-full rounded-full bg-accent" style={{ width: `${called * 100}%` }} />
                      </div>
                      <p className="mt-1 text-[11px] tabular-nums text-ink-3">
                        {f.pct(called * 100, 0)} of {f.money(x.committed)} called
                      </p>
                    </div>
                  </Link>
                );
              })}
          </div>
        </section>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
          <Panel className="xl:col-span-8">
            <PanelHead title="Fund comparison" icon={<Layers />} />
            <DataTable id="funds" chrome="minimal" label="Fund comparison" data={funds.data ?? []} status={funds.isLoading ? "loading" : "ready"} columns={cols} rowId={(x) => x.id} totals onRowOpen={(x) => router.push(`/app/funds/${x.slug}`)} empty={{ title: "No funds", body: "Funds appear when commitments are recorded." }} />
          </Panel>
          <ChartShell
            className="xl:col-span-4"
            title="Vintage comparison"
            subtitle={
              period === "latest"
                ? `${measureLabel} by vintage year, as of ${f.date(AS_OF)}`
                : `${measureLabel} since inception, ${PERIODS.find((p) => p.value === period)!.label.toLowerCase()} · ${PERIODS.find((p) => p.value === period)!.window}`
            }
            toolbar={
              <>
                <Segmented size="sm" label="Measure" value={measure} onChange={setMeasure} items={[{ value: "irr", label: "IRR" }, { value: "tvpi", label: "TVPI" }]} />
                <Select aria-label="Time period" value={period} onChange={(e) => setPeriod(e.target.value as Period)} className="w-[7.5rem] [&_select]:h-[30px] [&_select]:text-[12px]">
                  {PERIODS.map((p) => (
                    <option key={p.value} value={p.value}>
                      {p.label}
                    </option>
                  ))}
                </Select>
              </>
            }
            legend={
              period === "latest" ? (
                <Legend items={[{ label: measureLabel, color: "var(--color-chart-1)" }, ...(measure === "irr" && VINTAGE.some((v) => v.irr < 0) ? [{ label: "Negative", color: "var(--color-loss)" }] : [])]} />
              ) : (
                <Legend items={history.funds.map((h, i) => ({ label: `${h.fund} · ${h.vintage}`, color: SERIES[i] }))} />
              )
            }
            exportData={
              period === "latest"
                ? { filename: "vintage-comparison", head: ["Fund", "Vintage", "Net IRR %", "TVPI"], rows: vintage.map((v) => [v.fund, v.vintage, v.irr, v.tvpi.toFixed(2)]) }
                : { filename: `vintage-${measure}-${period}`, head: ["Period", ...history.funds.map((h) => `${h.fund} (${h.vintage})`)], rows: history.titles.map((t, i) => [t, ...history.funds.map((h) => (Number.isFinite(h[measure][i]) ? h[measure][i] : ""))]) }
            }
            height={300}
          >
            {({ expanded }) =>
              period === "latest" ? (
                <BarChart x={vintage.map((v) => `${v.vintage}`)} series={[{ id: "m", label: measureLabel, values: vintage.map((v) => (measure === "irr" ? v.irr : Math.round(v.tvpi * 100) / 100)) }]} format={fmt} label="Vintage comparison" signed />
              ) : (
                <VintageTrend period={period} measure={measure} expanded={expanded} format={fmt} />
              )
            }
          </ChartShell>
        </div>
      </PageBody>
    </>
  );
}

/** One since-inception line per vintage at the chosen granularity; the expanded chart shows a longer window. */
function VintageTrend({ period, measure, expanded, format }: { period: Granularity; measure: "irr" | "tvpi"; expanded: boolean; format: (v: number) => string }) {
  const h = useMemo(() => vintageHistory(period, expanded), [period, expanded]);
  return (
    <TrendChart
      x={h.x}
      titles={h.titles}
      series={h.funds.map((v, i) => ({ id: v.fund, label: `${v.fund} · ${v.vintage}`, values: v[measure], color: SERIES[i], area: false }))}
      format={format}
      label={`Vintage comparison, ${measure === "irr" ? "Net IRR" : "TVPI"} by ${period} period`}
      referenceLine={measure === "irr" ? { value: 0, label: "0%" } : { value: 1, label: "1.0×" }}
      dimAfterCursor={false}
    />
  );
}
