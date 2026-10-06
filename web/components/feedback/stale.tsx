"use client";

import { RotateCw } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

export type Freshness = "live" | "recent" | "stale" | "delayed" | "failed" | "demo" | "offline";

const FRESH: Record<Freshness, { label: string; dot: string }> = {
  live: { label: "Live", dot: "bg-ok" },
  recent: { label: "Updated recently", dot: "bg-ok" },
  stale: { label: "Stale", dot: "bg-warn" },
  delayed: { label: "Delayed", dot: "bg-warn" },
  failed: { label: "Refresh failed", dot: "bg-danger" },
  demo: { label: "Demo data", dot: "bg-info" },
  offline: { label: "Offline", dot: "bg-ink-4" },
};

/** One freshness vocabulary everywhere; never hides stale data (plan §31). */
export function FreshnessBadge({ state, asOf, className }: { state: Freshness; asOf?: string; className?: string }) {
  const f = FRESH[state];
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-[12px] text-ink-3", className)}>
      <span aria-hidden className={cn("size-1.5 rounded-full", f.dot)} />
      <span className="font-medium text-ink-2">{f.label}</span>
      {asOf && <span>· {asOf}</span>}
    </span>
  );
}

/** Explicit "as of" banner with source and refresh when data is past its freshness target. */
export function StaleBanner({ asOf, source, onRefresh, className }: { asOf: string; source?: string; onRefresh?: () => void; className?: string }) {
  return (
    <div role="status" className={cn("flex flex-wrap items-center gap-2 rounded-lg border border-warn/25 bg-warn/8 px-3 py-2 text-[12px] text-ink-2", className)}>
      <span aria-hidden className="size-1.5 rounded-full bg-warn" />
      <span>
        Showing data as of <span className="font-medium text-ink">{asOf}</span>
        {source && <> from {source}</>}. It is past its refresh target.
      </span>
      {onRefresh && (
        <Button size="xs" variant="ghost" className="ml-auto" onClick={onRefresh}>
          <RotateCw /> Refresh
        </Button>
      )}
    </div>
  );
}
