"use client";

import { useState } from "react";
import { ChartTooltip, niceTicks, SrTable, useSize, yGutter } from "./core";

/** Split an axis label into lines that fit the band; over-long words are shortened with an ellipsis. */
function fitLabel(label: string, band: number) {
  const max = Math.max(3, Math.floor((band - 4) / 5.8));
  const words = label.split(" ");
  const lines: string[] = [];
  for (const w of words) {
    const last = lines[lines.length - 1];
    if (last && `${last} ${w}`.length <= max) lines[lines.length - 1] = `${last} ${w}`;
    else lines.push(w.length > max ? `${w.slice(0, max - 1)}…` : w);
  }
  return lines.slice(0, 2);
}

/**
 * Value bridge (V2 FUNDS-010…012, ANA-020/021). Opening and closing totals are
 * neutral slate bars from an explicit zero line; increases float up in green,
 * decreases down in red, joined by connectors so the eye reads
 * opening + increases − decreases = closing. Values sit above increases and
 * below decreases; axis labels wrap or shorten instead of colliding.
 */
export function WaterfallChart({ data, label, format, axisFormat }: { data: { label: string; short?: string; value: number; kind: "total" | "step" }[]; label: string; format: (v: number) => string; axisFormat?: (v: number) => string }) {
  const [ref, { w, h }] = useSize<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  let run = 0;
  const bars = data.map((d) => {
    if (d.kind === "total") {
      run = d.value;
      return { ...d, from: 0, to: d.value };
    }
    const from = run;
    run += d.value;
    return { ...d, from, to: run };
  });
  const lo0 = Math.min(0, ...bars.map((b) => Math.min(b.from, b.to)));
  const hi0 = Math.max(...bars.map((b) => Math.max(b.from, b.to)));
  const ticks = niceTicks(lo0, hi0 * 1.06, 4);
  const lo = ticks[0];
  const hi = ticks[ticks.length - 1];
  const tick = axisFormat ?? format;
  const pl = yGutter(ticks.map(tick), w);
  const pr = 8;
  const pt = 20;
  const pb = 40;
  const Y = (v: number) => pt + ((hi - v) / (hi - lo || 1)) * (h - pt - pb);
  const band = (w - pl - pr) / bars.length;
  const bw = Math.max(10, Math.min(56, band * 0.56));
  const fmtSigned = (b: (typeof bars)[number]) => `${b.kind === "step" && b.value > 0 ? "+" : ""}${format(b.value)}`;

  return (
    <div ref={ref} className="relative h-full w-full">
      {w > 0 && h > 0 && (
        <svg width={w} height={h} role="img" aria-label={`${label}: ${bars.map((b) => `${b.label} ${fmtSigned(b)}`).join(", ")}`} onPointerLeave={() => setHover(null)} className="block overflow-visible">
          {ticks.map((t) => (
            <g key={t}>
              <line x1={pl} x2={w - pr} y1={Y(t)} y2={Y(t)} stroke="var(--color-chart-grid)" />
              <text x={pl - 8} y={Y(t) + 3.5} textAnchor="end" className="fill-ink-4 text-[11px] tabular-nums">
                {tick(t)}
              </text>
            </g>
          ))}
          {bars.map((b, i) => {
            const x = pl + band * i + (band - bw) / 2;
            const top = Y(Math.max(b.from, b.to));
            const bottom = Y(Math.min(b.from, b.to));
            const fill = b.kind === "total" ? "var(--color-mark-neutral)" : b.value >= 0 ? "var(--color-gain)" : "var(--color-loss)";
            const below = b.kind === "step" && b.value < 0;
            const lines = fitLabel(b.short && b.label.length * 6.2 > band - 6 ? b.short : b.label, band);
            return (
              <g key={b.label} onPointerEnter={() => setHover(i)} opacity={hover !== null && hover !== i ? 0.5 : 1}>
                <rect x={pl + band * i} y={pt} width={band} height={h - pt - pb} fill="transparent" />
                <rect x={x} y={top} width={bw} height={Math.max(3, bottom - top)} rx="2" fill={fill} />
                {i < bars.length - 1 && <line x1={x + bw} x2={x + band} y1={Y(b.to)} y2={Y(b.to)} stroke="var(--color-line-strong)" strokeDasharray="2 2" />}
                <text x={x + bw / 2} y={below ? bottom + 13 : top - 5} textAnchor="middle" className="fill-ink-2 text-[11px] font-medium tabular-nums">
                  {fmtSigned(b)}
                </text>
                <text x={x + bw / 2} y={h - pb + 16} textAnchor="middle" className="fill-ink-3 text-[11px]">
                  <title>{b.label}</title>
                  {lines.map((l, k) => (
                    <tspan key={k} x={x + bw / 2} dy={k === 0 ? 0 : 13}>
                      {l}
                    </tspan>
                  ))}
                </text>
              </g>
            );
          })}
          <line x1={pl} x2={w - pr} y1={Y(0)} y2={Y(0)} stroke="var(--color-ink-3)" strokeWidth="1.25" />
        </svg>
      )}
      {hover !== null && w > 0 && (
        <ChartTooltip
          x={pl + band * hover + band / 2}
          y={Y(Math.max(bars[hover].from, bars[hover].to))}
          width={w}
          title={bars[hover].label}
          rows={[
            { label: bars[hover].kind === "total" ? "Total" : "Change", value: fmtSigned(bars[hover]) },
            ...(bars[hover].kind === "step" ? [{ label: "Running total", value: format(bars[hover].to) }] : []),
          ]}
        />
      )}
      <SrTable caption={label} head={["Step", "Value", "Running total"]} rows={bars.map((b) => [b.label, fmtSigned(b), format(b.to)])} />
    </div>
  );
}
