"use client";

import { forwardRef, useRef } from "react";
import { Check, ChevronDown, Search, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { ring, ringInset } from "./button";

/* ---------- Tabs (roving focus, arrow keys) ---------- */

export function Tabs<T extends string>({
  value,
  onChange,
  items,
  label,
  variant = "underline",
  className,
}: {
  value: T;
  onChange: (v: T) => void;
  items: { value: T; label: string; count?: number; disabled?: boolean }[];
  label: string;
  /** underline = page/object tabs; pill = the one filter-pill style (TABS-001) used by every in-panel filter. */
  variant?: "underline" | "pill";
  className?: string;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const i = items.findIndex((t) => t.value === value);
  const onKeyDown = (e: React.KeyboardEvent) => {
    const map: Record<string, number> = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: items.length - 1 };
    if (!(e.key in map)) return;
    e.preventDefault();
    const n = (map[e.key] + items.length) % items.length;
    onChange(items[n].value);
    refs.current[n]?.focus();
  };
  return (
    <div
      role="tablist"
      aria-label={label}
      onKeyDown={onKeyDown}
      className={cn(
        "no-scrollbar flex overflow-x-auto overflow-y-hidden",
        variant === "underline" ? "h-10 items-stretch gap-5 border-b border-line" : "items-center gap-2 py-px",
        className,
      )}
    >
      {items.map((t, k) => (
        <button
          key={t.value}
          ref={(el) => {
            refs.current[k] = el;
          }}
          role="tab"
          type="button"
          aria-selected={t.value === value}
          tabIndex={t.value === value ? 0 : -1}
          disabled={t.disabled}
          onClick={() => onChange(t.value)}
          className={cn(
            "flex shrink-0 cursor-pointer items-center gap-1.5 text-[13px] font-medium transition-colors duration-150 disabled:cursor-default disabled:text-ink-4",
            variant === "underline" && "-mb-px border-b-2",
            variant === "underline" && (t.value === value ? "border-accent text-ink" : "border-transparent text-ink-3 hover:text-ink"),
            /* V2 PQ-001/002: 6px radius; active = light blue fill, blue text, blue border. */
            variant === "pill" && "h-8 whitespace-nowrap rounded-sm border px-3",
            variant === "pill" && (t.value === value ? "border-accent/60 bg-accent-soft font-semibold text-accent-ink" : "border-line bg-surface text-ink-2 hover:border-line-strong hover:bg-hover hover:text-ink"),
            variant === "pill" ? ring : ringInset,
          )}
        >
          {t.label}
          {t.count !== undefined && (
            <span className={cn("min-w-4 text-center font-data text-[11px] tabular-nums", variant === "pill" && t.value === value ? "text-accent-ink" : "text-ink-3")}>{t.count}</span>
          )}
        </button>
      ))}
    </div>
  );
}

/* ---------- Segmented control (radiogroup) ---------- */

export function Segmented<T extends string>({
  value,
  onChange,
  items,
  label,
  size = "md",
}: {
  value: T;
  onChange: (v: T) => void;
  items: { value: T; label: string }[];
  label: string;
  size?: "sm" | "md";
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex shrink-0 rounded-sm border border-line bg-muted p-0.5">
      {items.map((t) => (
        <button
          key={t.value}
          type="button"
          role="radio"
          aria-checked={t.value === value}
          onClick={() => onChange(t.value)}
          className={cn(
            "cursor-pointer whitespace-nowrap rounded-xs px-2.5 font-medium transition-colors duration-150",
            size === "sm" ? "h-6 text-[11px]" : "h-[30px] text-[12px]",
            t.value === value ? "bg-surface text-ink shadow-[0_0_0_1px_var(--color-line)]" : "text-ink-3 hover:text-ink",
            ring,
          )}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

/* ---------- Inputs ---------- */

const field =
  "h-8 w-full rounded-sm border border-line-strong bg-surface px-2.5 text-[13px] text-ink placeholder:text-ink-4 transition-colors hover:border-ink-4 focus:outline-none focus-visible:border-focus focus-visible:ring-2 focus-visible:ring-focus/25 disabled:cursor-not-allowed disabled:text-ink-4";

export const Input = forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...props }, ref) {
  return <input ref={ref} className={cn(field, className)} {...props} />;
});

export const SearchInput = forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(function SearchInput({ className, ...props }, ref) {
  return (
    <div className={cn("relative", className)}>
      <Search aria-hidden className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
      <input ref={ref} type="search" className={cn(field, "pl-8")} {...props} />
    </div>
  );
});

export function Select({ className, children, ...props }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className={cn("relative", className)}>
      <select className={cn(field, "cursor-pointer appearance-none pr-7")} {...props}>
        {children}
      </select>
      <ChevronDown aria-hidden className="pointer-events-none absolute right-2 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
    </div>
  );
}

export const Textarea = forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(function Textarea({ className, ...props }, ref) {
  return <textarea ref={ref} className={cn(field, "h-auto min-h-20 resize-y py-2", className)} {...props} />;
});

export function Checkbox({ checked, indeterminate, onChange, label, className }: { checked: boolean; indeterminate?: boolean; onChange: (v: boolean) => void; label: string; className?: string }) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={indeterminate ? "mixed" : checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={cn(
        "flex size-4 shrink-0 cursor-pointer items-center justify-center rounded-[4px] border transition-colors",
        checked || indeterminate ? "border-accent-fill bg-accent-fill text-white" : "border-line-strong bg-surface hover:border-ink-4",
        ring,
        className,
      )}
    >
      {checked && !indeterminate && <Check aria-hidden className="size-3" strokeWidth={3} />}
      {indeterminate && <span aria-hidden className="h-0.5 w-2 bg-white" />}
    </button>
  );
}

/** Label + control + hint/error. Errors are linked with aria-describedby. */
export function Field({ id, label, hint, error, required, children }: { id: string; label: string; hint?: string; error?: string; required?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-[12px] font-medium text-ink-2">
        {label}
        {required && <span className="text-danger"> *</span>}
      </label>
      {children}
      {error ? (
        <p id={`${id}-error`} className="mt-1 text-[12px] text-danger">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="mt-1 text-[12px] text-ink-3">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

/** On/off setting (plan §27 settings, Vestra appearance/alerts rows). */
export function Switch({ checked, onChange, label, disabled, className }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean; className?: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        "relative inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-50",
        checked ? "bg-accent" : "bg-line-strong",
        ring,
        className,
      )}
    >
      <span aria-hidden className={cn("size-4 rounded-full bg-white shadow-sm transition-transform duration-150", checked ? "translate-x-4.5" : "translate-x-0.5")} />
    </button>
  );
}

/** Removable applied-filter chip (plan §22): "Fund: Flagship II ×". */
export function FilterChip({ label, value, onRemove }: { label: string; value: string; onRemove: () => void }) {
  return (
    <span className="inline-flex h-7 items-center gap-1 rounded-full border border-accent-line bg-accent-soft pl-2.5 pr-1 text-[12px] text-ink">
      <span className="text-ink-3">{label}:</span>
      <span className="max-w-40 truncate font-medium">{value}</span>
      <button type="button" onClick={onRemove} aria-label={`Remove ${label} filter ${value}`} className={cn("ml-0.5 flex size-5 cursor-pointer items-center justify-center rounded-full text-ink-3 hover:bg-hover hover:text-ink", ring)}>
        <X aria-hidden className="size-3" />
      </button>
    </span>
  );
}
