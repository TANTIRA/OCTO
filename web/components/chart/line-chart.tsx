"use client";

import { useId, useState } from "react";
import { niceTicks, SERIES, SrTable, ChartTooltip, useSize, yGutter } from "./core";

export type Series = { id: string; label: string; values: number[]; color?: string; dashed?: boolean; area?: boolean };

/**
 * Trend chart (Vestra interaction model, token-registry.md): strong primary
 * line with a soft area fill, horizontal gridlines only, a full-height cursor
 * with point markers and tooltip, the series after the cursor dimmed, and
 * `onHover` so the panel headline can follow the cursor. Arrow keys move the
 * cursor when the chart has focus.
 */
export function TrendChart({
  x,
  series,
  format,
  label,
  onHover,
  zeroBased = false,
  referenceLine,
  titles,
  note,
  axisFormat,
  dimAfterCursor = true,
}: {
  x: string[];
  series: Series[];
  format: (v: number) => string;
  label: string;
  onHover?: (index: number | null) => void;
  zeroBased?: boolean;
  referenceLine?: { value: number; label: string };
  /** Long tooltip titles per point (e.g. "30 Sep 2026") when axis labels are abbreviated. */
  titles?: string[];
  /** Extra tooltip line per point, e.g. "+3.2% QoQ". */
  note?: (index: number) => React.ReactNode;
  /** Tick format when it should be coarser than the tooltip format. */
  axisFormat?: (v: number) => string;
  /** Fade the primary series after the cursor; off when every series is a peer (comparison charts). */
  dimAfterCursor?: boolean;
}) {
  const [ref, { w, h }] = useSize<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const gid = useId();
  const pr = 8;
  const pt = 8;
  const pb = 22;
  // Non-finite values are gaps (e.g. a fund before its first close): no line, marker or tooltip row.
  const ok = Number.isFinite;
  const all = series.flatMap((s) => s.values.filter(ok)).concat(referenceLine ? [referenceLine.value] : []);
  const ticks = niceTicks(zeroBased ? Math.min(0, ...all) : Math.min(...all), Math.max(...all), 4);
  const tick = axisFormat ?? format;
  const pl = yGutter(ticks.map(tick), w);
  const lo = ticks[0];
  const hi = ticks[ticks.length - 1];
  const X = (i: number) => pl + (i / Math.max(1, x.length - 1)) * (w - pl - pr);
  const Y = (v: number) => pt + ((hi - v) / (hi - lo || 1)) * (h - pt - pb);
  const path = (vals: number[], from = 0, to = vals.length - 1) => {
    let pen = false;
    let d = "";
    for (let i = from; i <= to; i++) {
      if (!ok(vals[i])) {
        pen = false;
        continue;
      }
      d += `${pen ? "L" : "M"}${X(i).toFixed(1)},${Y(vals[i]).toFixed(1)} `;
      pen = true;
    }
    return d.trim();
  };
  const show = (v: number) => (ok(v) ? format(v) : "—");
  const firstFinite = (vals: number[]) => vals.findIndex(ok);

  const set = (i: number | null) => {
    setHover(i);
    onHover?.(i);
  };
  const onMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left;
    const i = Math.round(((px - pl) / (w - pl - pr)) * (x.length - 1));
    set(Math.max(0, Math.min(x.length - 1, i)));
  };
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowRight") (e.preventDefault(), set(Math.min(x.length - 1, (hover ?? -1) + 1)));
    else if (e.key === "ArrowLeft") (e.preventDefault(), set(Math.max(0, (hover ?? x.length) - 1)));
    else if (e.key === "Escape") set(null);
  };
  const labelEvery = Math.max(1, Math.ceil(x.length / Math.max(2, Math.floor((w - pl) / 64))));
  const primary = series[0];
  const start = primary ? Math.max(0, firstFinite(primary.values)) : 0;
  const summary = primary ? `${label}: ${primary.label} from ${show(primary.values[start])} (${x[start]}) to ${show(primary.values[primary.values.length - 1])} (${x[x.length - 1]}).` : label;

  return (
    <div ref={ref} className="relative h-full w-full">
      {w > 0 && h > 0 && (
        <svg
          width={w}
          height={h}
          role="img"
          aria-label={summary}
          tabIndex={0}
          onPointerMove={onMove}
          onPointerLeave={() => set(null)}
          onKeyDown={onKey}
          onBlur={() => set(null)}
          className="block touch-none rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
        >
          <defs>
            {series.map((s, si) => (
              <linearGradient key={s.id} id={`${gid}-${si}`} x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor={s.color ?? SERIES[si]} stopOpacity="0.16" />
                <stop offset="100%" stopColor={s.color ?? SERIES[si]} stopOpacity="0" />
              </linearGradient>
            ))}
          </defs>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={pl} x2={w - pr} y1={Y(t)} y2={Y(t)} stroke="var(--color-chart-grid)" />
              <text x={pl - 8} y={Y(t) + 3.5} textAnchor="end" className="fill-ink-4 text-[11px] tabular-nums">
                {tick(t)}
              </text>
            </g>
          ))}
          {referenceLine && (
            <g>
              <line x1={pl} x2={w - pr} y1={Y(referenceLine.value)} y2={Y(referenceLine.value)} stroke="var(--color-ink-3)" strokeDasharray="4 3" />
              <text x={w - pr} y={Y(referenceLine.value) - 4} textAnchor="end" className="fill-ink-3 text-[10px]">
                {referenceLine.label}
              </text>
            </g>
          )}
          {x.map((l, i) =>
            (i % labelEvery === 0 && x.length - 1 - i >= labelEvery * 0.6) || i === x.length - 1 ? (
              <text key={i} x={X(i)} y={h - 6} textAnchor={i === 0 ? "start" : i === x.length - 1 ? "end" : "middle"} className="fill-ink-4 text-[11px]">
                {l}
              </text>
            ) : null,
          )}
          {series.map((s, si) => {
            const color = s.color ?? SERIES[si];
            const dimFrom = dimAfterCursor && si === 0 && hover !== null ? hover : null;
            return (
              <g key={s.id}>
                {s.area !== false && si === 0 && <path d={`${path(s.values)} L${X(s.values.length - 1)},${h - pb} L${X(Math.max(0, firstFinite(s.values)))},${h - pb} Z`} fill={`url(#${gid}-${si})`} opacity={dimFrom !== null ? 0.6 : 1} />}
                {dimFrom !== null ? (
                  <>
                    <path d={path(s.values, 0, dimFrom)} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" />
                    <path d={path(s.values, dimFrom)} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" opacity="0.3" />
                  </>
                ) : (
                  <path d={path(s.values)} fill="none" stroke={color} strokeWidth={si === 0 ? 2 : 1.5} strokeDasharray={s.dashed ? "4 3" : undefined} strokeLinejoin="round" />
                )}
              </g>
            );
          })}
          {hover !== null && (
            <g>
              <line x1={X(hover)} x2={X(hover)} y1={pt} y2={h - pb} stroke="var(--color-accent)" strokeOpacity="0.5" />
              {series.map((s, si) => ok(s.values[hover]) && (
                <circle key={s.id} cx={X(hover)} cy={Y(s.values[hover])} r="3.5" fill="var(--color-surface)" stroke={s.color ?? SERIES[si]} strokeWidth="2" />
              ))}
            </g>
          )}
        </svg>
      )}
      {hover !== null && w > 0 && (
        <ChartTooltip
          x={X(hover)}
          y={Math.min(...series.filter((s) => ok(s.values[hover])).map((s) => Y(s.values[hover])), h - pb)}
          width={w}
          title={titles?.[hover] ?? x[hover]}
          rows={series.map((s, si) => ({ label: s.label, value: show(s.values[hover]), color: s.color ?? SERIES[si] }))}
          note={note?.(hover)}
        />
      )}
      <SrTable caption={label} head={["Period", ...series.map((s) => s.label)]} rows={x.map((l, i) => [l, ...series.map((s) => show(s.values[i]))])} />
    </div>
  );
}
