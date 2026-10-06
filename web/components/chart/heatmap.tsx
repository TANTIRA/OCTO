"use client";

import { cn } from "@/lib/utils";
import { SrTable } from "./core";

/**
 * Diverging heatmap (plan §20 operational/risk family). Gain/loss tints scale
 * with magnitude; every cell also prints its value so colour is not the only
 * carrier. Rendered as a real <table> for keyboard and screen readers.
 */
export function Heatmap({ rows, columns, format, label }: { rows: { label: string; values: number[] }[]; columns: string[]; format: (v: number) => string; label: string }) {
  const max = Math.max(...rows.flatMap((r) => r.values.map(Math.abs)), 1);
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-1 text-[11px]" aria-label={label}>
        <thead>
          <tr>
            <th scope="col" className="sr-only">
              Row
            </th>
            {columns.map((c) => (
              <th key={c} scope="col" className="px-1 pb-1 text-center font-medium text-ink-4">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label}>
              <th scope="row" className="whitespace-nowrap pr-2 text-left font-medium text-ink-2">
                {r.label}
              </th>
              {r.values.map((v, i) => {
                const a = Math.abs(v) / max;
                return (
                  <td
                    key={i}
                    title={`${r.label} · ${columns[i]}: ${format(v)}`}
                    style={{ background: v === 0 ? "var(--color-muted)" : `color-mix(in oklab, var(--color-${v > 0 ? "gain" : "loss"}) ${Math.round(12 + a * 58)}%, var(--color-surface))` }}
                    className={cn("h-8 min-w-11 rounded-md text-center font-medium tabular-nums", a > 0.6 ? "text-white" : "text-ink-2")}
                  >
                    {format(v)}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <SrTable caption={label} head={["Row", ...columns]} rows={rows.map((r) => [r.label, ...r.values.map(format)])} />
    </div>
  );
}
