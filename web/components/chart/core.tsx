"use client";

import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";

/*
 * Chart primitives (plan §20). Charts render at real pixel size (measured with
 * ResizeObserver), so axis labels never scale or clip. Colours come from the
 * chart tokens; status colours are reserved for status.
 */

export const SERIES = ["var(--color-chart-1)", "var(--color-chart-2)", "var(--color-chart-3)", "var(--color-chart-4)", "var(--color-chart-5)", "var(--color-chart-6)", "var(--color-chart-7)", "var(--color-chart-8)"];

export function useSize<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setSize({ w: Math.round(e.contentRect.width), h: Math.round(e.contentRect.height) }));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, size] as const;
}

/** Rounded tick values covering [lo, hi]. */
export function niceTicks(lo: number, hi: number, count = 4): number[] {
  if (lo === hi) return [lo];
  const span = hi - lo;
  const step0 = span / count;
  const mag = Math.pow(10, Math.floor(Math.log10(step0)));
  const step = [1, 2, 2.5, 5, 10].map((s) => s * mag).find((s) => span / s <= count) ?? step0;
  // Ticks always enclose the data: floor below the minimum, ceil above the maximum,
  // so no line or bar ever runs past the top or bottom gridline (V2 NAV-006).
  const start = Math.floor(lo / step) * step;
  const end = Math.ceil(hi / step - 1e-9) * step;
  const out: number[] = [];
  for (let v = start; v <= end + step * 0.001; v += step) out.push(Math.round(v * 1e6) / 1e6);
  return out;
}

/** Visually hidden data table so every chart is reachable without the graphic (plan §29). */
export function SrTable({ caption, head, rows }: { caption: string; head: string[]; rows: (string | number)[][] }) {
  return (
    <table className="sr-only">
      <caption>{caption}</caption>
      <thead>
        <tr>
          {head.map((h) => (
            <th key={h} scope="col">
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i}>
            {r.map((c, j) => (j === 0 ? <th key={j} scope="row">{c}</th> : <td key={j}>{c}</td>))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Floating tooltip: label on top, then series rows with swatches (Vestra hierarchy). */
export function ChartTooltip({ x, y, width, title, rows, note, className }: { x: number; y: number; width: number; title: string; rows: { label: string; value: string; color?: string; sub?: string }[]; note?: React.ReactNode; className?: string }) {
  const left = Math.min(Math.max(x + 12, 4), width - 180);
  return (
    <div
      role="presentation"
      style={{ left, top: Math.max(y - 12, 0) }}
      className={cn("pointer-events-none absolute z-10 w-44 rounded-lg border border-line bg-raised px-2.5 py-2 text-[11px] shadow-popover", className)}
    >
      <p className="font-medium text-ink-3">{title}</p>
      <ul className="mt-1 space-y-0.5">
        {rows.map((r) => (
          <li key={r.label} className="flex items-center justify-between gap-2">
            <span className="flex min-w-0 items-center gap-1.5 text-ink-2">
              {r.color && <span aria-hidden className="size-2 shrink-0 rounded-[2px]" style={{ background: r.color }} />}
              <span className="truncate">{r.label}</span>
            </span>
            <span className="font-semibold tabular-nums text-ink">{r.value}</span>
          </li>
        ))}
      </ul>
      {rows.some((r) => r.sub) && <p className="mt-1 text-ink-4">{rows.find((r) => r.sub)?.sub}</p>}
      {note && <p className="mt-1 border-t border-line-subtle pt-1 font-medium tabular-nums text-ink-2">{note}</p>}
    </div>
  );
}

export function Legend({ items, className }: { items: { label: string; color: string; dashed?: boolean; value?: string }[]; className?: string }) {
  return (
    <ul className={cn("flex flex-wrap gap-x-4 gap-y-1 text-[12px] text-ink-3", className)}>
      {items.map((i) => (
        <li key={i.label} className="flex items-center gap-1.5">
          {i.dashed ? (
            <svg aria-hidden width="14" height="4">
              <line x1="0" x2="14" y1="2" y2="2" stroke={i.color} strokeWidth="2" strokeDasharray="3 2" />
            </svg>
          ) : (
            <span aria-hidden className="size-2 rounded-[2px]" style={{ background: i.color }} />
          )}
          {i.label}
          {i.value && <span className="font-medium tabular-nums text-ink-2">{i.value}</span>}
        </li>
      ))}
    </ul>
  );
}

/**
 * Y-axis gutter (V2 NAV-006): at least 64px on desktop, 56px on tablet and
 * 48px on phones, growing with the widest formatted tick (up to 88px) so
 * "$812.4M", "−12.5%" or "1,250.0×" never clip.
 */
export function yGutter(labels: string[], width: number) {
  const widest = Math.max(0, ...labels.map((l) => l.length)) * 6.6 + 14;
  const min = width > 0 && width < 480 ? 48 : width > 0 && width < 768 ? 56 : 64;
  return Math.min(88, Math.max(min, widest));
}
