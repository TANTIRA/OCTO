"use client";

import Link from "next/link";
import { GitBranch } from "lucide-react";
import { cn } from "@/lib/utils";
import { useFormat } from "@/lib/use-format";
import { Monogram, StatusBadge, type Tone } from "@/components/ui/badge";
import { ring } from "@/components/ui/button";
import { Sparkline } from "@/components/chart/sparkline";
import { Delta } from "@/components/metric/delta";

/*
 * Table cell vocabulary (plan §2.6, Vestra entity cell): identity first,
 * numbers right-aligned and tabular, status as labelled chips.
 */

/** Monogram + name + secondary metadata; the name links to the object page. */
export function EntityCell({ name, sub, href, monogram = true, status }: { name: string; sub?: string; href?: string; monogram?: boolean; status?: React.ReactNode }) {
  const title = href ? (
    <Link href={href} className={cn("truncate rounded-sm font-semibold text-ink hover:text-accent", ring)}>
      {name}
    </Link>
  ) : (
    <span className="truncate font-semibold text-ink">{name}</span>
  );
  return (
    <span className="flex min-w-0 items-center gap-2.5">
      {monogram && <Monogram name={name} size="sm" />}
      <span className="flex min-w-0 flex-col leading-tight">
        <span className="flex min-w-0 items-center gap-1.5 text-[13px]">
          {title}
          {status}
        </span>
        {sub && <span className="truncate text-[11px] text-ink-3">{sub}</span>}
      </span>
    </span>
  );
}

/** Compact value with full precision on hover (plan §32: compact in lists, precise on detail). */
export function NumericCell({ value, kind = "money", muted, digits }: { value: number | null | undefined; kind?: "money" | "pct" | "multiple" | "number"; muted?: boolean; digits?: number }) {
  const f = useFormat();
  if (value == null) return <span className="text-ink-4">—</span>;
  const text = kind === "money" ? f.money(value, digits) : kind === "pct" ? f.pct(value, digits) : kind === "multiple" ? f.multiple(value) : f.num(value, digits ?? 0);
  return (
    <span title={kind === "money" ? f.moneyFull(value) : undefined} className={cn("tabular-nums", muted ? "text-ink-3" : "font-medium text-ink")}>
      {text}
    </span>
  );
}

export function DeltaCell({ value, unit = "%", upIsGood = true }: { value: number | null | undefined; unit?: "%" | "pts" | "×" | "$" | ""; upIsGood?: boolean }) {
  if (value == null) return <span className="text-ink-4">—</span>;
  return <Delta value={value} unit={unit} upIsGood={upIsGood} pill />;
}

export function StatusCell({ tone, children }: { tone: Tone; children: React.ReactNode }) {
  return <StatusBadge tone={tone}>{children}</StatusBadge>;
}

/**
 * Row trend line. With a `risk` status the line takes the status colour, so it
 * can never read as healthy next to an "At risk" badge; without one it colours
 * by direction over the window.
 */
export function SparklineCell({ values, upIsGood = true, risk }: { values: number[]; upIsGood?: boolean; risk?: "On track" | "Watch" | "At risk" }) {
  const rising = values[values.length - 1] >= values[0];
  const tone = risk ? ({ "On track": "gain", Watch: "warn", "At risk": "loss" } as const)[risk] : rising === upIsGood ? "gain" : "loss";
  return <Sparkline values={values} tone={tone} className="h-6 w-20" />;
}

/** Age of the underlying data against its target (e.g. valuation marks: 30 days). */
export function FreshnessCell({ ageDays, targetDays }: { ageDays: number; targetDays: number }) {
  const tone = ageDays > targetDays ? "text-warn" : "text-ink-3";
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-[12px] tabular-nums", tone)}>
      <span aria-hidden className={cn("size-1.5 rounded-full", ageDays > targetDays ? "bg-warn" : "bg-ok")} />
      {ageDays}d{ageDays > targetDays && <span className="sr-only"> (past {targetDays}-day target)</span>}
    </span>
  );
}

/** Opens the lineage drawer for the row's headline number. */
export function ProvenanceCell({ onOpen, label }: { onOpen: () => void; label: string }) {
  return (
    <button type="button" onClick={onOpen} aria-label={`Lineage for ${label}`} className={cn("inline-flex size-6 cursor-pointer items-center justify-center rounded-md text-ink-4 hover:bg-hover hover:text-accent", ring)}>
      <GitBranch aria-hidden className="size-3.5" />
    </button>
  );
}
