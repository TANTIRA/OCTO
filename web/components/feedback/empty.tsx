import { CircleSlash } from "lucide-react";
import { cn } from "@/lib/utils";

/** Explains what is missing, why it matters, and what creates the first item (plan §31). */
export function EmptyState({ icon, title, body, action, className }: { icon?: React.ReactNode; title: string; body: string; action?: React.ReactNode; className?: string }) {
  return (
    <div className={cn("flex flex-col items-center justify-center px-6 py-12 text-center", className)}>
      <span className="flex size-9 items-center justify-center rounded-lg border border-line bg-subtle text-ink-3 [&_svg]:size-4">{icon ?? <CircleSlash />}</span>
      <p className="mt-3 text-sm font-medium text-ink">{title}</p>
      <p className="mt-1 max-w-sm text-[13px] text-ink-3">{body}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
