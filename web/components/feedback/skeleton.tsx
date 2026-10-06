import { cn } from "@/lib/utils";

/** Loading placeholder that preserves final geometry — no layout shift (plan §31). */
export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden className={cn("block animate-pulse rounded-sm bg-muted motion-reduce:animate-none", className)} />;
}

/** KPI-card-shaped skeleton. */
export function MetricSkeleton() {
  return (
    <div aria-hidden className="flex h-[132px] flex-col justify-between rounded-lg border border-line bg-surface p-4">
      <Skeleton className="h-3 w-24" />
      <div className="space-y-2">
        <Skeleton className="h-7 w-28" />
        <Skeleton className="h-3 w-36" />
      </div>
    </div>
  );
}

/** Screen-reader announcement for a region that is loading. */
export function LoadingState({ label, className, children }: { label: string; className?: string; children?: React.ReactNode }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true" className={className}>
      <span className="sr-only">Loading {label}…</span>
      {children}
    </div>
  );
}
