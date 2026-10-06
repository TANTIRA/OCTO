"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";
import type { RiskPoint } from "@/lib/demo";
import { ChartTooltip, niceTicks, SrTable, useSize, yGutter } from "./core";

/**
 * Risk vs return scatter (V2 ANA-001…003). X = risk (volatility of marks),
 * Y = return (gross IRR). Four very light quadrant zones split at the median
 * risk and the return hurdle; one restrained colour for every dot (no rainbow
 * palette, no trend line). Hover or arrow keys show company, fund, risk,
 * return and position; click or Enter opens the entity drawer.
 */
export function RiskReturnChart({ points, hurdle, money, label, onSelect }: { points: RiskPoint[]; hurdle: number; money: (v: number) => string; label: string; onSelect: (p: RiskPoint) => void }) {
  const [ref, { w, h }] = useSize<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const sorted = [...points].sort((a, b) => a.risk - b.risk);
  if (!points.length) return <div ref={ref} className="flex h-full items-center justify-center text-[13px] text-ink-3">No positions match these filters.</div>;

  const xt = niceTicks(0, Math.max(...points.map((p) => p.risk)) * 1.08, 5);
  const yt = niceTicks(Math.min(0, ...points.map((p) => p.ret)), Math.max(hurdle, ...points.map((p) => p.ret)) * 1.08, 4);
  const pct = (v: number) => `${v < 0 ? "−" : ""}${Math.abs(v).toFixed(0)}%`;
  const pl = yGutter(yt.map(pct), w);
  const pr = 12;
  const pt = 12;
  const pb = 40;
  const X = (v: number) => pl + ((v - xt[0]) / (xt[xt.length - 1] - xt[0] || 1)) * (w - pl - pr);
  const Y = (v: number) => pt + ((yt[yt.length - 1] - v) / (yt[yt.length - 1] - yt[0] || 1)) * (h - pt - pb);
  const medRisk = sorted[Math.floor(sorted.length / 2)].risk;
  const cx = X(medRisk);
  const cy = Y(hurdle);
  const zones = [
    { x: pl, y: pt, w: cx - pl, h: cy - pt, label: "Low risk · high return", fill: "var(--color-mark-ok)", anchor: "start" as const, tx: pl + 8, ty: pt + 14 },
    { x: cx, y: pt, w: w - pr - cx, h: cy - pt, label: "High risk · high return", fill: "var(--color-mark-info)", anchor: "end" as const, tx: w - pr - 8, ty: pt + 14 },
    { x: pl, y: cy, w: cx - pl, h: h - pb - cy, label: "Low risk · low return", fill: "var(--color-mark-neutral)", anchor: "start" as const, tx: pl + 8, ty: h - pb - 8 },
    { x: cx, y: cy, w: w - pr - cx, h: h - pb - cy, label: "High risk · low return", fill: "var(--color-mark-warn)", anchor: "end" as const, tx: w - pr - 8, ty: h - pb - 8 },
  ];
  const cur = hover !== null ? sorted[hover] : null;

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowRight") (e.preventDefault(), setHover(Math.min(sorted.length - 1, (hover ?? -1) + 1)));
    else if (e.key === "ArrowLeft") (e.preventDefault(), setHover(Math.max(0, (hover ?? sorted.length) - 1)));
    else if (e.key === "Enter" && cur) (e.preventDefault(), onSelect(cur));
    else if (e.key === "Escape") setHover(null);
  };

  return (
    <div ref={ref} className="relative h-full w-full">
      {w > 0 && h > 0 && (
        <svg
          width={w}
          height={h}
          role="img"
          tabIndex={0}
          aria-label={`${label}: ${points.length} positions. Use arrow keys to step through positions by risk and Enter to open one.`}
          onKeyDown={onKey}
          onPointerLeave={() => setHover(null)}
          onBlur={() => setHover(null)}
          className="block rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
        >
          {zones.map((z) => (
            <g key={z.label}>
              <rect x={z.x} y={z.y} width={Math.max(0, z.w)} height={Math.max(0, z.h)} fill={z.fill} opacity="0.045" />
              <text x={z.tx} y={z.ty} textAnchor={z.anchor} className="fill-ink-4 text-[10px] font-medium uppercase tracking-[0.05em]">
                {z.label}
              </text>
            </g>
          ))}
          {yt.map((t) => (
            <g key={`y${t}`}>
              <line x1={pl} x2={w - pr} y1={Y(t)} y2={Y(t)} stroke={t === 0 ? "var(--color-ink-3)" : "var(--color-chart-grid)"} />
              <text x={pl - 8} y={Y(t) + 3.5} textAnchor="end" className="fill-ink-4 text-[11px] tabular-nums">
                {pct(t)}
              </text>
            </g>
          ))}
          {xt.map((t) => (
            <text key={`x${t}`} x={X(t)} y={h - pb + 16} textAnchor="middle" className="fill-ink-4 text-[11px] tabular-nums">
              {pct(t)}
            </text>
          ))}
          <line x1={cx} x2={cx} y1={pt} y2={h - pb} stroke="var(--color-line-strong)" strokeDasharray="3 3" />
          <line x1={pl} x2={w - pr} y1={cy} y2={cy} stroke="var(--color-line-strong)" strokeDasharray="3 3" />
          <text x={w - pr} y={cy - 4} textAnchor="end" className="fill-ink-3 text-[10px]">
            {hurdle}% hurdle
          </text>
          <text x={(pl + w - pr) / 2} y={h - 6} textAnchor="middle" className="fill-ink-3 text-[11px]">
            Risk — volatility of quarterly marks, annualised
          </text>
          <text transform={`translate(10 ${(pt + h - pb) / 2}) rotate(-90)`} textAnchor="middle" className="fill-ink-3 text-[11px]">
            Return — gross IRR
          </text>
          {sorted.map((p, i) => (
            <circle
              key={p.id}
              cx={X(p.risk)}
              cy={Y(p.ret)}
              r={hover === i ? 7 : 5.5}
              fill="var(--color-chart-1)"
              fillOpacity={hover === null || hover === i ? 0.85 : 0.25}
              stroke="var(--color-surface)"
              strokeWidth="1.5"
              className={cn("cursor-pointer transition-[r,fill-opacity] duration-150")}
              onPointerEnter={() => setHover(i)}
              onClick={() => onSelect(p)}
            />
          ))}
        </svg>
      )}
      {cur && w > 0 && (
        <ChartTooltip
          x={X(cur.risk)}
          y={Y(cur.ret)}
          width={w}
          title={cur.company}
          rows={[
            { label: "Fund", value: cur.fund },
            { label: "Risk", value: `${cur.risk.toFixed(1)}%` },
            { label: "Return", value: `${cur.ret.toFixed(1)}%` },
            { label: "Position", value: money(cur.fv) },
          ]}
          note={`${cur.sector} · ${cur.instrument}`}
        />
      )}
      <SrTable caption={label} head={["Company", "Fund", "Risk %", "Return %", "Position"]} rows={sorted.map((p) => [p.company, p.fund, p.risk.toFixed(1), p.ret.toFixed(1), money(p.fv)])} />
    </div>
  );
}
