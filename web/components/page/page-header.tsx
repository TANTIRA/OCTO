import { Suspense } from "react";
import { cn } from "@/lib/utils";
import { PageStateGate } from "./page-state";

/**
 * Page header (plan §9). Every page uses the same order:
 * eyebrow/context → title + description → freshness/meta → actions → tabs.
 *
 * Variants change density, not structure:
 *   dashboard/list/workflow/settings — 32px/600 page title (parity TYPE-001)
 *   object — 26px title, identity slot (monogram/logo) and status row
 *   builder — compact, sticky-friendly
 */
export function PageHeader({
  eyebrow,
  title,
  description,
  meta,
  actions,
  tabs,
  identity,
  variant = "list",
  className,
}: {
  eyebrow?: React.ReactNode;
  title: string;
  description?: string;
  meta?: React.ReactNode;
  actions?: React.ReactNode;
  tabs?: React.ReactNode;
  identity?: React.ReactNode;
  variant?: "dashboard" | "list" | "object" | "workflow" | "builder" | "settings";
  className?: string;
}) {
  return (
    <header className={cn("border-b border-line bg-app", className)}>
      <div className={cn("mx-auto flex w-full max-w-[1600px] flex-col gap-3 px-4 sm:px-6 lg:px-8 md:flex-row md:items-end md:justify-between", variant === "builder" ? "py-3" : "pb-4 pt-5")}>
        <div className="flex min-w-0 items-start gap-3.5">
          {identity}
          <div className="min-w-0">
            {eyebrow && <div className="text-[12px] font-medium text-ink-3">{eyebrow}</div>}
            <h1 className={cn("mt-0.5 font-semibold text-ink", variant === "object" ? "text-object" : "text-page")}>{title}</h1>
            {description && <p className="mt-1.5 max-w-2xl text-[14px] text-ink-3">{description}</p>}
            {meta && <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5">{meta}</div>}
          </div>
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {tabs && <div className="mx-auto w-full max-w-[1600px] px-4 sm:px-6 lg:px-8">{tabs}</div>}
    </header>
  );
}

/** Page body: gutters 16 → 24 → 28px (Vestra), bounded at 1600px; renders through the page state gate (V2 §30). */
export function PageBody({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div className={cn("mx-auto w-full max-w-[1600px] px-4 py-5 sm:px-6 lg:px-8 lg:pb-12", className)}>
      <Suspense fallback={children}>
        <PageStateGate>{children}</PageStateGate>
      </Suspense>
    </div>
  );
}

export const PageContainer = PageBody;

/** A titled vertical section inside a page body. */
export function PageSection({ title, description, actions, className, children }: { title?: string; description?: string; actions?: React.ReactNode; className?: string; children: React.ReactNode }) {
  return (
    <section className={cn("min-w-0", className)} aria-label={title}>
      {(title || actions) && (
        <div className="mb-3 flex items-end justify-between gap-3">
          <div>
            {title && <h2 className="text-section font-semibold text-ink">{title}</h2>}
            {description && <p className="mt-0.5 text-[12px] text-ink-3">{description}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

/** Toolbar row for filters and view controls directly under the header. */
export function PageToolbar({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn("flex flex-wrap items-center gap-2", className)}>{children}</div>;
}
