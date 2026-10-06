"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";
import { SERIES, SrTable } from "./core";

/**
 * Allocation donut (Vestra: thick ring, rounded segment ends, percentage in the
 * centre, legend below). Category colour is data encoding, not decoration.
 * Hovering or focusing a legend row highlights its segment; with `onSelect`,
 * clicking a row or segment drills into that category.
 */
export function DonutChart({ data, label, format, centerLabel, size = 184, onSelect, selectHint = "Show positions" }: { data: { key: string; value: number }[]; label: string; format: (v: number) => string; centerLabel?: string; size?: number; onSelect?: (key: string) => void; selectHint?: string }) {
  const [active, setActive] = useState<number | null>(null);
  const total = data.reduce((n, d) => n + d.value, 0) || 1;
  const r = size / 2 - 14;
  const c = 2 * Math.PI * r;
  const gap = data.length > 1 ? 4 : 0;
  let offset = 0;
  const shown = active ?? 0;
  const pct = (v: number) => `${((v / total) * 100).toFixed(1)}%`;

  return (
    <figure className="flex flex-col items-center">
      <div className="relative" style={{ width: size, height: size }}>
        <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={`${label}: ${data.map((d) => `${d.key} ${pct(d.value)}`).join(", ")}`} className="-rotate-90">
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--color-muted)" strokeWidth="18" />
          {data.map((d, i) => {
            const len = Math.max(0, (d.value / total) * c - gap);
            const el = (
              <circle
                key={d.key}
                cx={size / 2}
                cy={size / 2}
                r={r}
                fill="none"
                stroke={SERIES[i % SERIES.length]}
                strokeWidth={active === i ? 22 : 18}
                strokeLinecap="round"
                strokeDasharray={`${len} ${c - len}`}
                strokeDashoffset={-offset}
                opacity={active !== null && active !== i ? 0.35 : 1}
                className="transition-[stroke-width,opacity] duration-150"
                onPointerEnter={() => setActive(i)}
                onPointerLeave={() => setActive(null)}
                onClick={onSelect ? () => onSelect(d.key) : undefined}
                style={onSelect ? { cursor: "pointer" } : undefined}
              >
                <title>{`${d.key}: ${pct(d.value)} · ${format(d.value)}`}</title>
              </circle>
            );
            offset += (d.value / total) * c;
            return el;
          })}
        </svg>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className="text-kpi font-semibold tabular-nums text-ink">{pct(data[shown]?.value ?? 0)}</span>
          <span className="mt-0.5 max-w-[60%] truncate text-[12px] text-ink-3">{data[shown]?.key ?? centerLabel}</span>
          {active !== null && <span className="mt-0.5 text-[11px] tabular-nums text-ink-4">{format(data[active].value)}</span>}
        </div>
      </div>
      <ul className="mt-4 grid w-full grid-cols-1 gap-x-3 gap-y-0.5 pb-1 min-[480px]:grid-cols-2">
        {data.map((d, i) => {
          const row = cn("flex w-full items-center gap-2 rounded-md px-1.5 py-1 text-left text-[12px] hover:bg-hover focus-visible:outline-2 focus-visible:outline-focus", active === i && "bg-hover");
          const content = (
            <>
              <span aria-hidden className="size-2 shrink-0 rounded-[2px]" style={{ background: SERIES[i % SERIES.length] }} />
              <span className="min-w-0 flex-1 truncate text-ink-2" title={d.key}>
                {d.key}
              </span>
              <span className="shrink-0 tabular-nums text-ink-3">{pct(d.value)}</span>
            </>
          );
          return (
            <li key={d.key} onPointerEnter={() => setActive(i)} onPointerLeave={() => setActive(null)}>
              {onSelect ? (
                <button type="button" onClick={() => onSelect(d.key)} onFocus={() => setActive(i)} onBlur={() => setActive(null)} aria-label={`${selectHint}: ${d.key}, ${pct(d.value)}`} className={cn(row, "cursor-pointer")}>
                  {content}
                </button>
              ) : (
                // Without a drill target the row is a legend, not a control.
                <div className={row}>{content}</div>
              )}
            </li>
          );
        })}
      </ul>
      <SrTable caption={label} head={["Category", "Value", "Share"]} rows={data.map((d) => [d.key, format(d.value), pct(d.value)])} />
    </figure>
  );
}
