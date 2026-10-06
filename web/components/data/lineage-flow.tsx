"use client";

import { Fragment } from "react";
import { AlertTriangle, ArrowDown, ArrowRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { ringInset } from "@/components/ui/button";

const NODE_H = 48;
const GAP = 8;
const HEAD = 64;

export type FlowNode = { id: string; label: string; flag?: "warn" | "danger" };
export type FlowLayer = { layer: string; about?: string; nodes: FlowNode[] };

/**
 * Lineage flow (V3 LINEAGE-002). Equal-width layer columns with equal-height
 * nodes; the highlighted path (one node per layer) is joined by connectors and
 * everything else stays neutral. Controlled: the caller owns the path and
 * decides what selecting a node does (re-route, open its detail drawer).
 */
export function LineageFlow({ layers, path, label, onSelect }: { layers: FlowLayer[]; path: string[]; label: string; onSelect: (layer: number, node: FlowNode) => void }) {
  const idx = layers.map((l, li) => Math.max(0, l.nodes.findIndex((n) => n.id === path[li])));
  const y = (i: number) => HEAD + i * (NODE_H + GAP) + NODE_H / 2;
  const rows = Math.max(...layers.map((l) => l.nodes.length));
  const height = HEAD + rows * NODE_H + (rows - 1) * GAP;
  const names = layers.map((l, li) => l.nodes[idx[li]]?.label);

  return (
    <figure aria-label={label}>
      <figcaption className="mb-4 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[13px]">
        <span className="mr-1 text-ink-3">Highlighted path:</span>
        {names.map((n, i) => (
          <Fragment key={`${i}-${n}`}>
            <span className="font-medium text-ink">{n}</span>
            {i < names.length - 1 && <ArrowRight aria-hidden className="size-3.5 text-ink-4" />}
          </Fragment>
        ))}
      </figcaption>

      <div className="hidden overflow-x-auto md:block">
        <div className="grid min-w-[760px]" style={{ gridTemplateColumns: layers.map(() => "minmax(0,1fr)").join(" 40px "), height }}>
          {layers.map((l, li) => (
            <Fragment key={l.layer}>
              <Column layer={l} li={li} selected={idx[li]} onPick={(n) => onSelect(li, n)} />
              {li < layers.length - 1 && (
                <svg aria-hidden width="40" height={height} className="overflow-visible">
                  <path d={`M0 ${y(idx[li])} C 20 ${y(idx[li])}, 20 ${y(idx[li + 1])}, 40 ${y(idx[li + 1])}`} fill="none" stroke="var(--color-accent)" strokeWidth="1.75" />
                  <circle cx="38" cy={y(idx[li + 1])} r="2.5" fill="var(--color-accent)" />
                </svg>
              )}
            </Fragment>
          ))}
        </div>
      </div>

      {/* Phones: stacked layers, arrows between. */}
      <div className="space-y-2 md:hidden">
        {layers.map((l, li) => (
          <Fragment key={l.layer}>
            <Column layer={l} li={li} selected={idx[li]} onPick={(n) => onSelect(li, n)} />
            {li < layers.length - 1 && <ArrowDown aria-hidden className="mx-auto size-4 text-accent" />}
          </Fragment>
        ))}
      </div>
    </figure>
  );
}

function Column({ layer, li, selected, onPick }: { layer: FlowLayer; li: number; selected: number; onPick: (n: FlowNode) => void }) {
  return (
    <div className="min-w-0">
      <div className="overflow-hidden" style={{ height: HEAD - 8, marginBottom: 8 }}>
        <p className="text-label uppercase text-ink-4">
          {li + 1}. {layer.layer}
        </p>
        {layer.about && <p className="mt-0.5 line-clamp-2 text-[12px] leading-snug text-ink-3">{layer.about}</p>}
      </div>
      <ul className="flex flex-col" style={{ gap: GAP }} aria-label={layer.layer}>
        {layer.nodes.map((n, ni) => {
          const on = ni === selected;
          return (
            <li key={n.id}>
              <button
                type="button"
                aria-pressed={on}
                aria-haspopup="dialog"
                onClick={() => onPick(n)}
                style={{ height: NODE_H }}
                className={cn(
                  "flex w-full cursor-pointer items-center gap-2 rounded-md border px-3 text-left text-[13px] font-medium transition-colors",
                  on ? "border-accent bg-accent-soft text-accent-ink" : "border-line bg-surface text-ink-2 hover:border-line-strong hover:bg-hover",
                  ringInset,
                )}
              >
                <span aria-hidden className={cn("size-2 shrink-0 rounded-full", on ? "bg-accent" : "bg-line-strong")} />
                <span className="min-w-0 flex-1 truncate" title={n.label}>
                  {n.label}
                </span>
                {n.flag && (
                  <span className={cn("flex shrink-0 items-center [&>svg]:size-3.5", n.flag === "danger" ? "text-mark-danger" : "text-mark-warn")}>
                    <AlertTriangle aria-hidden />
                    <span className="sr-only">{n.flag === "danger" ? "Failing" : "Needs attention"}</span>
                  </span>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
