"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Database, RotateCw } from "lucide-react";
import { Button, LinkButton } from "@/components/ui/button";
import { EmptyState, ErrorState, InlineAlert, MetricSkeleton, PermissionState, Skeleton } from "@/components/feedback";

export type PageState = "loading" | "empty" | "error" | "partial" | "stale" | "permission";
const STATES: PageState[] = ["loading", "empty", "error", "partial", "stale", "permission"];

/**
 * Page state gate (V2 §30). Every page body renders through this, so every
 * major page has designed loading / empty / error / partial / stale /
 * permission states. In the demo they are previewed with `?state=<name>`; a
 * live data layer would drive the same props. The page header stays visible
 * so people keep their place; loading keeps the final geometry.
 */
export function PageStateGate({ children }: { children: React.ReactNode }) {
  const params = useSearchParams();
  const router = useRouter();
  const path = usePathname();
  const raw = params.get("state");
  const state = STATES.includes(raw as PageState) ? (raw as PageState) : null;
  if (!state) return <>{children}</>;

  const clear = () => {
    const p = new URLSearchParams(params.toString());
    p.delete("state");
    router.replace(`${path}${p.size ? `?${p}` : ""}`, { scroll: false });
  };

  switch (state) {
    case "loading":
      return (
        <div role="status" aria-busy="true" className="space-y-4">
          <span className="sr-only">Loading this page…</span>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
            {Array.from({ length: 6 }, (_, i) => (
              <MetricSkeleton key={i} />
            ))}
          </div>
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
            <Skeleton className="h-80 rounded-lg xl:col-span-8" />
            <Skeleton className="h-80 rounded-lg xl:col-span-4" />
          </div>
        </div>
      );
    case "empty":
      return (
        <div className="rounded-lg border border-line bg-surface">
          <EmptyState
            icon={<Database />}
            title="Nothing to show for this workspace yet"
            body="No funds, positions or workflow items have been loaded, because no data source is connected. Connect an administrator or upload a file to start."
            action={<LinkButton variant="primary" href="/app/data">Connect a source</LinkButton>}
          />
        </div>
      );
    case "error":
      return (
        <div className="rounded-lg border border-line bg-surface">
          <ErrorState title="We couldn’t load this page." scope="The OCTO API did not respond in time. Nothing you entered was lost. Try again, or keep working on another page." reference="REQ-7F3A-2209" onRetry={clear} />
          <p className="-mt-6 pb-8 text-center">
            <button type="button" onClick={clear} className="cursor-pointer text-[12px] font-medium text-accent-ink hover:underline">
              View last loaded data
            </button>
          </p>
        </div>
      );
    case "permission":
      return (
        <div className="rounded-lg border border-line bg-surface">
          <PermissionState scope="this page" grantor="a workspace admin" />
        </div>
      );
    case "stale":
      return (
        <div className="space-y-4">
          <InlineAlert tone="warn" title="Showing data from the last successful refresh" action={<Button size="sm" onClick={clear}><RotateCw /> Refresh</Button>}>
            Last updated 30 Sep 2026, 09:12 UTC — past the 4-hour refresh target. Figures may not reflect today’s activity.
          </InlineAlert>
          {children}
        </div>
      );
    case "partial":
      return (
        <div className="space-y-4">
          <InlineAlert tone="warn" title="Some data didn’t load" action={<Button size="sm" onClick={clear}><RotateCw /> Retry</Button>}>
            The News &amp; filings feed failed. Everything from other sources is shown; signals and news matches may be incomplete.
          </InlineAlert>
          {children}
        </div>
      );
  }
}
