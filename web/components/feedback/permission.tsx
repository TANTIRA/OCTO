import { Lock } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Restricted content (plan §31). Never names or counts the hidden object —
 * only says access is limited and who can grant it.
 */
export function PermissionState({ scope = "this content", grantor = "a workspace admin", className, compact }: { scope?: string; grantor?: string; className?: string; compact?: boolean }) {
  if (compact)
    return (
      <span className={cn("inline-flex items-center gap-1 text-[12px] text-ink-3", className)}>
        <Lock aria-hidden className="size-3" /> Restricted
      </span>
    );
  return (
    <div role="note" className={cn("flex flex-col items-center justify-center px-6 py-12 text-center", className)}>
      <span className="flex size-9 items-center justify-center rounded-lg border border-line bg-subtle text-ink-3">
        <Lock aria-hidden className="size-4" />
      </span>
      <p className="mt-3 text-sm font-medium text-ink">You don’t have access to {scope}</p>
      <p className="mt-1 max-w-sm text-[13px] text-ink-3">Ask {grantor} for access. Restricted items are not listed or counted here.</p>
    </div>
  );
}
