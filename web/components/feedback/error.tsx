"use client";

import { AlertTriangle, RotateCw } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

/** Readable reason, scope, retry, and a support reference (plan §31). */
export function ErrorState({ title, scope, reference, onRetry, className }: { title: string; scope: string; reference?: string; onRetry?: () => void; className?: string }) {
  return (
    <div role="alert" className={cn("flex flex-col items-center justify-center px-6 py-12 text-center", className)}>
      <span className="flex size-9 items-center justify-center rounded-lg border border-danger/25 bg-danger/10 text-danger">
        <AlertTriangle aria-hidden className="size-4" />
      </span>
      <p className="mt-3 text-sm font-medium text-ink">{title}</p>
      <p className="mt-1 max-w-sm text-[13px] text-ink-3">{scope}</p>
      {reference && <p className="mt-1 font-data text-[11px] text-ink-3">Reference {reference}</p>}
      {onRetry && (
        <Button className="mt-4" size="sm" onClick={onRetry}>
          <RotateCw /> Retry
        </Button>
      )}
    </div>
  );
}
