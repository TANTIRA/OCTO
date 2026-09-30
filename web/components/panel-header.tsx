import type { ReactNode } from "react";
import { RefreshCw } from "lucide-react";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

/**
 * Toolbar row for an app-shell area. The shell header already carries the
 * area title, so a panel only states what the surface does, its refresh
 * control, and the outcome of the last action.
 */
export default function PanelHeader({
  description,
  onRefresh,
  refreshing = false,
  refreshLabel = "Refresh",
  error,
  notice,
  children,
}: {
  description: ReactNode;
  onRefresh?: () => void;
  refreshing?: boolean;
  refreshLabel?: string;
  error?: string | null;
  notice?: string | null;
  children?: ReactNode;
}) {
  return (
    <>
      <header className="flex shrink-0 flex-wrap items-center gap-3 pb-4">
        <p className="min-w-0 flex-1 text-[13px] text-neutral-500">{description}</p>
        {children}
        {onRefresh && (
          <button
            type="button"
            onClick={onRefresh}
            disabled={refreshing}
            aria-label={refreshLabel}
            title={refreshLabel}
            className="inline-flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] border border-neutral-200/70 text-neutral-600 hover:bg-neutral-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent)] disabled:cursor-default disabled:opacity-60 dark:border-neutral-800 dark:text-neutral-400 dark:hover:bg-neutral-900"
          >
            <RefreshCw
              aria-hidden
              className={cx("h-4 w-4", refreshing && "animate-spin motion-reduce:animate-none")}
            />
          </button>
        )}
      </header>
      {error && (
        <p
          role="alert"
          className="mb-3 rounded-[var(--rb-r-lg,10px)] border border-red-200 bg-red-50 px-3 py-2 text-[13px] text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300"
        >
          {error}
        </p>
      )}
      {notice && (
        <p
          role="status"
          className="mb-3 rounded-[var(--rb-r-lg,10px)] border border-emerald-200 bg-emerald-50 px-3 py-2 text-[13px] text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300"
        >
          {notice}
        </p>
      )}
    </>
  );
}
