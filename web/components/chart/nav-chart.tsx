"use client";

import { useMemo, useState } from "react";
import { Check, ChevronDown } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { AS_OF, NAV_SERIES, RANGE_DELTA, navSeries, type NavRange } from "@/lib/demo";
import { Button } from "@/components/ui/button";
import { Menu } from "@/components/ui/overlay";
import { FreshnessBadge } from "@/components/feedback";
import { ChartShell, type ChartState } from "./chart-shell";
import { Legend } from "./core";
import { TrendChart } from "./line-chart";

const RANGES: { value: NavRange; label: string }[] = [
  { value: "daily", label: "Daily" },
  { value: "weekly", label: "Weekly" },
  { value: "monthly", label: "Monthly" },
  { value: "quarterly", label: "Quarterly" },
  { value: "yearly", label: "Yearly" },
];

/** Range ▾ dropdown — the first of the shared chart controls (CHART-001). */
export function RangeMenu({ value, onChange, label = "Range" }: { value: NavRange; onChange: (v: NavRange) => void; label?: string }) {
  const current = RANGES.find((r) => r.value === value)!;
  return (
    <Menu
      label={`${label} options`}
      items={RANGES.map((r) => ({ label: r.label, icon: r.value === value ? <Check /> : <span className="size-3.5" />, onSelect: () => onChange(r.value) }))}
      trigger={({ ref, open, toggle }) => (
        <Button ref={ref} size="sm" aria-haspopup="menu" aria-expanded={open} aria-label={`${label}: ${current.label}`} onClick={toggle}>
          {current.label} <ChevronDown aria-hidden />
        </Button>
      )}
    />
  );
}

/**
 * Shared NAV chart (CHART-003). One component for Control Center, Portfolio and
 * Analytics: Range ▾ (Daily → Yearly), Download, Expand; a headline that follows
 * the cursor; tooltip "Q3 26 · NAV $812.4M · +3.2% QoQ"; a y-axis gutter wide
 * enough for "$812.4M". Every range ends at the reconciled quarter-end mark.
 */
export function NavChart({
  title = "Portfolio NAV",
  points = NAV_SERIES,
  benchmark = true,
  defaultRange = "quarterly",
  state = "ready",
  onLineage,
  toolbar,
  height = 260,
  expandable = true,
  downloadable = true,
  className,
}: {
  title?: string;
  points?: { q: string; nav: number; benchmark: number }[];
  benchmark?: boolean;
  defaultRange?: NavRange;
  state?: ChartState;
  onLineage?: () => void;
  toolbar?: React.ReactNode;
  height?: number;
  expandable?: boolean;
  downloadable?: boolean;
  className?: string;
}) {
  const f = useFormat();
  const [range, setRange] = useState<NavRange>(defaultRange);
  const [hover, setHover] = useState<number | null>(null);
  const data = useMemo(() => navSeries(range, points), [range, points]);
  const full = useMemo(() => navSeries(range, points, true), [range, points]);
  const i = hover ?? data.length - 1;
  const cur = data[i];
  const change = (k: number) => (k === 0 ? null : ((data[k].nav - data[k - 1].nav) / data[k - 1].nav) * 100);
  // Range changes reset the cursor so the headline never points past the new series.
  const pick = (r: NavRange) => (setHover(null), setRange(r));
  const money = (v: number) => `$${v.toFixed(1)}M`;
  const axis = (v: number) => `$${Math.round(v)}M`;
  const delta = change(i);

  return (
    <ChartShell
      className={className}
      title={title}
      subtitle={`${RANGES.find((r) => r.value === range)!.label} NAV, $M · hover or use arrow keys to inspect`}
      headline={
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1" aria-live="polite">
          <span className="text-kpi-xl font-semibold tabular-nums text-ink">{money(cur.nav)}</span>
          <span className="text-[12px] font-medium text-ink-3">
            {cur.title}
            {delta !== null && (
              <>
                {" · "}
                <span className={delta >= 0 ? "text-ok" : "text-danger"}>{f.delta(delta)}</span> {RANGE_DELTA[range]}
              </>
            )}
          </span>
        </div>
      }
      toolbar={
        <>
          {toolbar}
          <RangeMenu value={range} onChange={pick} />
        </>
      }
      legend={benchmark ? <Legend items={[{ label: "NAV", color: "var(--color-chart-1)" }, { label: "Public benchmark (rebased)", color: "var(--color-ink-4)", dashed: true }]} /> : undefined}
      freshness={<FreshnessBadge state="demo" asOf={f.date(AS_OF)} />}
      source="IBOR valuations"
      onLineage={onLineage}
      exportData={downloadable ? { filename: `nav-${range}`, head: ["Date", "NAV ($M)", ...(benchmark ? ["Benchmark ($M)"] : [])], rows: full.map((p) => [p.date, p.nav, ...(benchmark ? [p.benchmark] : [])]) } : undefined}
      state={state}
      height={height}
      expandable={expandable}
    >
      {({ expanded }) => {
        const d = expanded ? full : data;
        const delta = (k: number) => (k === 0 ? null : ((d[k].nav - d[k - 1].nav) / d[k - 1].nav) * 100);
        return (
          <TrendChart
            key={`${range}-${expanded}`}
            x={d.map((p) => p.label)}
            titles={d.map((p) => p.title)}
            series={[{ id: "nav", label: "NAV", values: d.map((p) => p.nav) }, ...(benchmark ? [{ id: "bm", label: "Benchmark", values: d.map((p) => p.benchmark), color: "var(--color-ink-4)", dashed: true, area: false }] : [])]}
            format={money}
            axisFormat={axis}
            note={(k) => {
              const c = delta(k);
              return c === null ? `${money(d[k].nav)} · start of range` : `${money(d[k].nav)} · ${f.delta(c)} ${RANGE_DELTA[range]}`;
            }}
            label={`${title}, ${range}${expanded ? ", full history" : ""}`}
            onHover={expanded ? undefined : setHover}
          />
        );
      }}
    </ChartShell>
  );
}

/** V2 shared-primitive name for the range control. */
export { RangeMenu as RangeSelect };
