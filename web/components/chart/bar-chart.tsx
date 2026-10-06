"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";
import { ChartTooltip, niceTicks, SERIES, SrTable, useSize, yGutter } from "./core";

type BarSeries = { id: string; label: string; values: number[]; color?: string };

/**
 * Vertical bars — grouped (side by side) or stacked. Supports negative values
 * with an explicit zero line. `signed` colours negative bars red (CHART-005:
 * vintage IRR below zero must read as a loss, not as another blue bar).
 */
export function BarChart({ x, series, format, label, stacked = false, signed = false }: { x: string[]; series: BarSeries[]; format: (v: number) => string; label: string; stacked?: boolean; signed?: boolean }) {
  const [ref, { w, h }] = useSize<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const pb = 22;
  const pt = 8;
  const totals = x.map((_, i) => series.reduce((n, s) => n + Math.max(0, s.values[i]), 0));
  const negs = x.map((_, i) => series.reduce((n, s) => n + Math.min(0, s.values[i]), 0));
  const all = stacked ? [...totals, ...negs, 0] : [...series.flatMap((s) => s.values), 0];
  const ticks = niceTicks(Math.min(...all), Math.max(...all), 4);
  const pl = yGutter(ticks.map(format), w);
  const lo = ticks[0];
  const hi = ticks[ticks.length - 1];
  const Y = (v: number) => pt + ((hi - v) / (hi - lo || 1)) * (h - pt - pb);
  const band = (w - pl) / x.length;
  const groupW = Math.min(band * 0.64, stacked ? 40 : 18 * series.length + 4);
  const barW = stacked ? groupW : (groupW - (series.length - 1) * 3) / series.length;

  return (
    <div ref={ref} className="relative h-full w-full">
      {w > 0 && h > 0 && (
        <svg width={w} height={h} role="img" aria-label={`${label}, ${x.length} categories`} onPointerLeave={() => setHover(null)} className="block">
          {ticks.map((t) => (
            <g key={t}>
              <line x1={pl} x2={w} y1={Y(t)} y2={Y(t)} stroke="var(--color-chart-grid)" />
              <text x={pl - 8} y={Y(t) + 3.5} textAnchor="end" className="fill-ink-4 text-[11px] tabular-nums">
                {format(t)}
              </text>
            </g>
          ))}
          {x.map((cat, i) => {
            const cx = pl + band * i + band / 2;
            let up = 0;
            let down = 0;
            return (
              <g key={cat} onPointerEnter={() => setHover(i)} className={cn(hover !== null && hover !== i && "opacity-45", "transition-opacity duration-150")}>
                <rect x={pl + band * i} y={pt} width={band} height={h - pt - pb} fill="transparent" />
                {series.map((s, si) => {
                  const v = s.values[i];
                  const color = s.color ?? SERIES[si];
                  if (stacked) {
                    const base = v >= 0 ? up : down;
                    const top = base + v;
                    if (v >= 0) up = top;
                    else down = top;
                    return <rect key={s.id} x={cx - groupW / 2} y={Y(Math.max(base, top))} width={groupW} height={Math.max(1, Math.abs(Y(base) - Y(top)))} fill={color} rx="2" />;
                  }
                  const bx = cx - groupW / 2 + si * (barW + 3);
                  return <rect key={s.id} x={bx} y={Y(Math.max(0, v))} width={barW} height={Math.max(1, Math.abs(Y(0) - Y(v)))} fill={signed && v < 0 ? "var(--color-loss)" : color} rx="2" />;
                })}
                <text x={cx} y={h - 6} textAnchor="middle" className="fill-ink-4 text-[11px]">
                  {cat}
                </text>
              </g>
            );
          })}
          {/* Explicit zero line, always (V3 VINTAGE-003). */}
          <line x1={pl} x2={w} y1={Y(0)} y2={Y(0)} stroke="var(--color-ink-3)" strokeWidth="1.25" />
        </svg>
      )}
      {hover !== null && w > 0 && (
        <ChartTooltip
          x={pl + band * hover + band / 2}
          y={Y(stacked ? totals[hover] : Math.max(...series.map((s) => s.values[hover])))}
          width={w}
          title={x[hover]}
          rows={series.map((s, si) => ({ label: s.label, value: format(s.values[hover]), color: signed && s.values[hover] < 0 ? "var(--color-loss)" : (s.color ?? SERIES[si]) }))}
        />
      )}
      <SrTable caption={label} head={["Category", ...series.map((s) => s.label)]} rows={x.map((c, i) => [c, ...series.map((s) => format(s.values[i]))])} />
    </div>
  );
}

/** Horizontal ranking bars with value labels — allocation and movers (plan §2.8, §2.9). */
export function RankingBars({ items, format, label, color = "var(--color-chart-1)", max }: { items: { label: string; value: number; sub?: string; href?: string; color?: string }[]; format: (v: number) => string; label: string; color?: string; max?: number }) {
  const top = max ?? Math.max(...items.map((i) => Math.abs(i.value)), 1);
  return (
    <figure aria-label={label}>
      <ul className="space-y-2.5">
        {items.map((i) => (
          <li key={i.label}>
            <div className="flex items-baseline justify-between gap-3 text-[12px]">
              <span className="min-w-0 truncate font-medium text-ink-2">{i.label}</span>
              <span className="shrink-0 tabular-nums font-semibold text-ink">{format(i.value)}</span>
            </div>
            <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
              <div className="h-full rounded-full" style={{ width: `${(Math.abs(i.value) / top) * 100}%`, background: i.color ?? color }} />
            </div>
            {i.sub && <p className="mt-0.5 text-[11px] text-ink-4">{i.sub}</p>}
          </li>
        ))}
      </ul>
    </figure>
  );
}
