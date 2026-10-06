"use client";

import { forwardRef, useEffect, useState } from "react";
import { ListFilter } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button, ringInset } from "@/components/ui/button";
import { CountBadge } from "@/components/ui/badge";
import { Sheet } from "@/components/ui/overlay";

export type FilterField = { id: string; label: string; options: [string, number][] };
export type FilterValue = Record<string, string[]>;

export const activeFilterCount = (v: FilterValue) => Object.values(v).reduce((n, x) => n + (x.length ? 1 : 0), 0);

/** The one visible filter control on every list page (V3 FILTER-001): "Filters 3". */
export const FilterButton = forwardRef<HTMLButtonElement, { count: number; open: boolean; onClick: () => void; className?: string }>(function FilterButton({ count, open, onClick, className }, ref) {
  return (
    <Button ref={ref} size="sm" aria-haspopup="dialog" aria-expanded={open} onClick={onClick} className={cn(count > 0 && "border-accent/60 bg-accent-soft text-accent-ink", className)}>
      <ListFilter /> Filters
      {count > 0 && (
        <CountBadge tone="accent">
          {count}
          <span className="sr-only"> active</span>
        </CountBadge>
      )}
    </Button>
  );
});

/**
 * Filters drawer (V3 FILTER-002). One right-side sheet (full-width on phones)
 * listing every filterable field with its options and counts. Changes are a
 * draft until Apply; Clear all empties the draft. Escape or close discards.
 */
export function FilterDrawer({ open, onClose, title = "Filters", fields, value, onApply }: { open: boolean; onClose: () => void; title?: string; fields: FilterField[]; value: FilterValue; onApply: (v: FilterValue) => void }) {
  const [draft, setDraft] = useState<FilterValue>(value);
  useEffect(() => {
    if (open) setDraft(value);
  }, [open, value]);
  const toggle = (fid: string, opt: string) =>
    setDraft((d) => {
      const cur = d[fid] ?? [];
      return { ...d, [fid]: cur.includes(opt) ? cur.filter((x) => x !== opt) : [...cur, opt] };
    });
  const n = activeFilterCount(draft);
  return (
    <Sheet
      open={open}
      onClose={onClose}
      eyebrow={n ? `${n} active` : "No filters applied"}
      title={title}
      footer={
        <div className="flex items-center justify-between gap-2">
          <Button variant="ghost" disabled={n === 0} onClick={() => setDraft({})}>
            Clear all
          </Button>
          <Button
            variant="primary"
            onClick={() => {
              onApply(Object.fromEntries(Object.entries(draft).filter(([, v]) => v.length)));
              onClose();
            }}
          >
            Apply
          </Button>
        </div>
      }
    >
      <div className="space-y-5">
        {fields.map((fl) => {
          const sel = draft[fl.id] ?? [];
          return (
            <fieldset key={fl.id}>
              <legend className="mb-2 flex w-full items-baseline justify-between text-[13px] font-semibold text-ink">
                {fl.label}
                {sel.length > 0 && <span className="text-[12px] font-normal text-accent-ink">{sel.length} selected</span>}
              </legend>
              <div className="flex flex-wrap gap-1.5">
                {fl.options.map(([opt, count]) => {
                  const on = sel.includes(opt);
                  return (
                    <button
                      key={opt}
                      type="button"
                      aria-pressed={on}
                      onClick={() => toggle(fl.id, opt)}
                      className={cn(
                        "inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-sm border px-2.5 text-[12px]",
                        on ? "border-accent/60 bg-accent-soft font-medium text-accent-ink" : "border-line bg-surface text-ink-2 hover:border-line-strong hover:bg-hover",
                        ringInset,
                      )}
                    >
                      {opt}
                      <span className={cn("tabular-nums", on ? "text-accent-ink" : "text-ink-4")}>{count}</span>
                    </button>
                  );
                })}
              </div>
            </fieldset>
          );
        })}
      </div>
    </Sheet>
  );
}
