"use client";

import { forwardRef, useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Download, GitBranch, Maximize2, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { IconButton } from "@/components/ui/button";
import { useFocusTrap } from "@/components/ui/overlay";
import { EmptyState, ErrorState, PermissionState } from "@/components/feedback";

export type ChartState = "ready" | "loading" | "empty" | "error" | "stale" | "permission";

export type ChartExport = { filename: string; head: string[]; rows: (string | number)[][] };

export function downloadCsv({ filename, head, rows }: ChartExport) {
  const esc = (v: string | number) => {
    const s = String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const csv = [head.map(esc).join(","), ...rows.map((r) => r.map(esc).join(","))].join("\n");
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  Object.assign(document.createElement("a"), { href: url, download: `${filename}.csv` }).click();
  URL.revokeObjectURL(url);
}

/* ---------- ChartToolbar primitives (V2 §26: range/filter → Download → Expand) ---------- */

export function DownloadButton({ title, data }: { title: string; data: ChartExport }) {
  return <IconButton size="sm" variant="secondary" label={`Download ${title} as CSV`} icon={<Download />} onClick={() => downloadCsv(data)} />;
}

export const ExpandButton = forwardRef<HTMLButtonElement, { title: string; onClick: () => void }>(function ExpandButton({ title, onClick }, ref) {
  return <IconButton ref={ref} size="sm" variant="secondary" label={`Expand ${title}`} aria-haspopup="dialog" icon={<Maximize2 />} onClick={onClick} />;
});

export function ChartToolbar({ children }: { children: React.ReactNode }) {
  return <div className="flex flex-wrap items-center gap-1.5">{children}</div>;
}

type Render = React.ReactNode | ((ctx: { expanded: boolean }) => React.ReactNode);

/**
 * ChartCard (V2 §26 / §28). Title, subtitle, toolbar, plot, legend, footer;
 * designed loading / empty / error / stale / permission states. Expand opens a
 * separate overlay (max 90vw × 85vh) so the page never shifts; children given
 * as a function receive `expanded` and can show deeper history there. Escape
 * closes and focus returns to the Expand button.
 */
export function ChartShell({
  title,
  subtitle,
  icon,
  headline,
  toolbar,
  legend,
  freshness,
  source,
  onLineage,
  state = "ready",
  staleSince,
  emptyText = "No data for the selected period.",
  onRetry,
  exportData,
  height = 260,
  expandable = true,
  className,
  children,
}: {
  title: string;
  subtitle?: string;
  icon?: React.ReactNode;
  headline?: React.ReactNode;
  toolbar?: React.ReactNode;
  legend?: React.ReactNode;
  freshness?: React.ReactNode;
  source?: string;
  onLineage?: () => void;
  state?: ChartState;
  /** Shown as "Last updated …" when the chart is stale. */
  staleSince?: string;
  emptyText?: string;
  onRetry?: () => void;
  exportData?: ChartExport;
  /** Minimum plot height in px — the plot grows to fill a taller card — or "auto" when the body sizes itself (donut + legend). */
  height?: number | "auto";
  expandable?: boolean;
  className?: string;
  children: Render;
}) {
  const [full, setFull] = useState(false);
  const expandRef = useRef<HTMLButtonElement>(null);
  const render = (expanded: boolean) => (typeof children === "function" ? children({ expanded }) : children);

  const body =
    state === "loading" ? (
      <div role="status" aria-busy="true" className="flex flex-col justify-end gap-2" style={{ height: height === "auto" ? 220 : height }}>
        <span className="sr-only">Loading {title}…</span>
        <div className="flex h-full items-end gap-2">
          {[0.45, 0.6, 0.5, 0.75, 0.65, 0.85, 0.7, 0.9].map((h, i) => (
            <span key={i} aria-hidden style={{ height: `${h * 100}%` }} className="block flex-1 animate-pulse rounded-sm bg-muted motion-reduce:animate-none" />
          ))}
        </div>
      </div>
    ) : state === "empty" ? (
      <EmptyState title="Nothing to chart" body={emptyText} className="py-6" />
    ) : state === "error" ? (
      <ErrorState title={`We couldn’t load the latest ${title.replace(/^Portfolio /, "")}.`} scope="The rest of the page still works. The last loaded values are kept until a refresh succeeds." onRetry={onRetry} className="py-6" />
    ) : state === "permission" ? (
      <PermissionState scope="this chart" className="py-6" />
    ) : (
      height === "auto" ? (
        <div className={cn(state === "stale" && "opacity-80")}>{render(false)}</div>
      ) : (
        // V3 §23: the plot flex-fills whatever height the card gets; `height` is its minimum, never a cap.
        <div style={{ minHeight: height }} className={cn("relative flex-1", state === "stale" && "opacity-80")}>
          <div className="absolute inset-0">{render(false)}</div>
        </div>
      )
    );

  const ready = state === "ready" || state === "stale";

  return (
    <section aria-label={title} className={cn("flex min-w-0 flex-col rounded-lg border border-line bg-surface", className)}>
      <header className="flex flex-wrap items-start justify-between gap-3 px-5 pt-4">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 text-card font-semibold text-ink [&>svg]:size-4 [&>svg]:text-ink-3">
            {icon}
            {title}
          </h2>
          {subtitle && <p className="mt-0.5 text-[12px] text-ink-3">{subtitle}</p>}
          {headline && <div className="mt-2">{headline}</div>}
        </div>
        <ChartToolbar>
          {toolbar}
          {exportData && ready && <DownloadButton title={title} data={exportData} />}
          {expandable && ready && <ExpandButton ref={expandRef} title={title} onClick={() => setFull(true)} />}
        </ChartToolbar>
      </header>
      {legend && <div className="px-5 pt-2">{legend}</div>}
      <div className="flex min-h-0 flex-1 flex-col px-5 pb-4 pt-3">{body}</div>
      {(freshness || source || onLineage || state === "stale") && (
        <footer className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-line-subtle px-5 py-2.5 text-[11px] text-ink-3">
          {freshness}
          {source && <span>Source: {source}</span>}
          {state === "stale" && <span className="font-medium text-warn">Stale — last updated {staleSince ?? "before the refresh target"}</span>}
          {onLineage && (
            <button type="button" onClick={onLineage} className="ml-auto inline-flex cursor-pointer items-center gap-1 rounded-sm font-medium hover:text-accent focus-visible:outline-2 focus-visible:outline-focus">
              <GitBranch aria-hidden className="size-3" /> Lineage
            </button>
          )}
        </footer>
      )}
      {full && (
        <ExpandedChart title={title} subtitle={subtitle} headline={headline} toolbar={toolbar} legend={legend} exportData={exportData} onClose={() => setFull(false)}>
          {render(true)}
        </ExpandedChart>
      )}
    </section>
  );
}

function ExpandedChart({ title, subtitle, headline, toolbar, legend, exportData, onClose, children }: { title: string; subtitle?: string; headline?: React.ReactNode; toolbar?: React.ReactNode; legend?: React.ReactNode; exportData?: ChartExport; onClose: () => void; children: React.ReactNode }) {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  if (!mounted) return null;
  return createPortal(
    <ExpandedBody title={title} subtitle={subtitle} headline={headline} toolbar={toolbar} legend={legend} exportData={exportData} onClose={onClose}>
      {children}
    </ExpandedBody>,
    document.querySelector(".octo-app") ?? document.body,
  );
}

function ExpandedBody({ title, subtitle, headline, toolbar, legend, exportData, onClose, children }: { title: string; subtitle?: string; headline?: React.ReactNode; toolbar?: React.ReactNode; legend?: React.ReactNode; exportData?: ChartExport; onClose: () => void; children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  useFocusTrap(ref, onClose);
  return (
    <div className="fixed inset-0 z-[75] flex items-center justify-center p-3 sm:p-6">
      <div aria-hidden className="absolute inset-0 bg-black/40 motion-safe:animate-[fade-in_160ms_ease-out]" onClick={onClose} />
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="relative flex h-[85vh] w-full max-w-[90vw] flex-col rounded-lg border border-line bg-surface shadow-dialog motion-safe:animate-[pop-in_160ms_var(--ease-out-soft)]"
      >
        <header className="flex flex-wrap items-start justify-between gap-3 border-b border-line-subtle px-6 py-4">
          <div className="min-w-0">
            <h2 id={titleId} className="text-section font-semibold text-ink">
              {title} <span className="text-[13px] font-normal text-ink-3">· full history</span>
            </h2>
            {subtitle && <p className="mt-0.5 text-[12px] text-ink-3">{subtitle}</p>}
            {headline && <div className="mt-2">{headline}</div>}
          </div>
          <ChartToolbar>
            {toolbar}
            {exportData && <DownloadButton title={title} data={exportData} />}
            <IconButton size="sm" variant="secondary" label="Close expanded chart" icon={<X />} onClick={onClose} data-autofocus />
          </ChartToolbar>
        </header>
        {legend && <div className="px-6 pt-3">{legend}</div>}
        <div className="min-h-0 flex-1 px-6 pb-6 pt-3">{children}</div>
      </div>
    </div>
  );
}

/** V2 shared-primitive name for the chart container. */
export { ChartShell as ChartCard };

/** V3 shared-primitive names: ChartFrame and ExpandableChart are the same component. */
export { ChartShell as ChartFrame, ChartShell as ExpandableChart };
