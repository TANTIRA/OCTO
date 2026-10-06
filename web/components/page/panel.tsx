import { cn } from "@/lib/utils";
import { EmptyState } from "@/components/feedback";

/*
 * Panel family (parity CARD-001). One card geometry for every page: white
 * surface, 1px #E5E7EB hairline, 10px radius, no shadow, 20px padding, header
 * aligned to the card edge. Pages never invent their own card styles.
 */

export function Panel({ className, children, as: As = "section", ...props }: React.HTMLAttributes<HTMLElement> & { as?: "section" | "div" | "article" }) {
  return (
    <As className={cn("flex min-w-0 flex-col rounded-lg border border-line bg-surface", className)} {...props}>
      {children}
    </As>
  );
}

export function PanelHeader({ className, children, divider = false }: { className?: string; children: React.ReactNode; divider?: boolean }) {
  return <header className={cn("flex min-h-14 items-center justify-between gap-3 px-5 pt-4", divider ? "border-b border-line pb-3" : "pb-1", className)}>{children}</header>;
}

export function PanelTitle({ icon, children, className }: { icon?: React.ReactNode; children: React.ReactNode; className?: string }) {
  return (
    <h2 className={cn("flex min-w-0 items-center gap-2 text-card font-semibold text-ink [&>svg]:size-4 [&>svg]:shrink-0 [&>svg]:text-ink-3", className)}>
      {icon}
      <span className="truncate">{children}</span>
    </h2>
  );
}

export function PanelDescription({ children, className }: { children: React.ReactNode; className?: string }) {
  return <p className={cn("text-[12px] text-ink-3", className)}>{children}</p>;
}

export function PanelToolbar({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn("flex shrink-0 items-center gap-1.5", className)}>{children}</div>;
}

/**
 * Panel body. With `fill`, the body takes all remaining card height (min `fill`
 * px) and scrolls inside — so a list beside a taller neighbour never leaves the
 * card hanging with empty space (V3 §03, PQ-001).
 */
export function PanelBody({ className, children, flush, fill, label }: { className?: string; children: React.ReactNode; flush?: boolean; fill?: number; label?: string }) {
  if (fill)
    return (
      <div className="relative min-h-0 flex-1" style={{ minHeight: fill }}>
        {/* Keyboard users can focus and scroll the region even when it holds no links (axe scrollable-region-focusable). */}
        <div tabIndex={0} role="region" aria-label={label ?? "Scrollable list"} className={cn("absolute inset-0 overflow-y-auto overscroll-contain focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-focus", flush ? "" : "p-5 pt-3", className)}>
          {children}
        </div>
      </div>
    );
  return <div className={cn("min-h-0 flex-1", flush ? "" : "p-5 pt-3", className)}>{children}</div>;
}

export function PanelFooter({ className, children }: { className?: string; children: React.ReactNode }) {
  return <footer className={cn("flex items-center justify-between gap-3 border-t border-line-subtle px-5 py-3 text-[12px] text-ink-3", className)}>{children}</footer>;
}

export function PanelEmpty(props: React.ComponentProps<typeof EmptyState>) {
  return <EmptyState {...props} className={cn("py-10", props.className)} />;
}

/** Title + optional description + toolbar, the common header layout. */
export function PanelHead({ title, icon, description, toolbar, divider }: { title: string; icon?: React.ReactNode; description?: React.ReactNode; toolbar?: React.ReactNode; divider?: boolean }) {
  return (
    <PanelHeader divider={divider}>
      <div className="min-w-0">
        <PanelTitle icon={icon}>{title}</PanelTitle>
        {description && <PanelDescription className="mt-0.5">{description}</PanelDescription>}
      </div>
      {toolbar && <PanelToolbar>{toolbar}</PanelToolbar>}
    </PanelHeader>
  );
}
