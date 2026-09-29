"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, RefreshCw } from "lucide-react";
import { getJson, messageFor } from "@/lib/api";
import { TenantPicker, useTenants } from "@/lib/use-tenants";

/**
 * Agent runs, live: `GET /api/v1/agent-runs?tenantId` lists the audit spine
 * (F4) — every sidecar workflow run with its status, models and verdict.
 * This is the transparency surface for "what did the AI do": no narrative
 * editing, read-only, server-scoped.
 */

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type Run = {
  id: string;
  workflow: string;
  subjectType: string;
  subjectId: string;
  status: string;
  models: { drafter?: string; judge?: string } | null;
  createdAt?: string;
  error?: string | null;
};

const STATUS_DOT: Record<string, string> = {
  running: "bg-amber-500",
  completed: "bg-emerald-500",
  refused: "bg-red-500",
  failed: "bg-red-500",
};

export default function AgentRunsPanel() {
  const { tenants, tenantId, setTenantId, loading: tenantsLoading, error: tenantError } =
    useTenants();
  const [runs, setRuns] = useState<Run[]>([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true);
    setError(null);
    try {
      setRuns(await getJson<Run[]>(`/api/v1/agent-runs?tenantId=${tenantId}&limit=50`));
      setLoaded(true);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setLoading(false);
    }
  }, [tenantId]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="flex h-full min-h-[680px] flex-col bg-white dark:bg-neutral-950">
      <header className="flex shrink-0 flex-wrap items-center gap-3 px-6 pt-6 pb-4 sm:px-8">
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-medium tracking-[-0.01em] text-neutral-900 dark:text-neutral-100">
            Alerts &amp; agents
          </h2>
          <p className="mt-0.5 text-[13px] text-neutral-500">
            Every agent workflow run, with its models and judge verdict — the
            audit spine, read-only.
          </p>
        </div>
        <TenantPicker tenants={tenants} tenantId={tenantId} onChange={setTenantId} />
        <button
          type="button"
          onClick={load}
          aria-label="Refresh runs"
          className="inline-flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] border border-neutral-200/70 text-neutral-600 hover:bg-neutral-50 dark:border-neutral-800 dark:text-neutral-400 dark:hover:bg-neutral-900"
        >
          <RefreshCw
            aria-hidden
            className={cx("h-4 w-4", loading && "animate-spin motion-reduce:animate-none")}
          />
        </button>
      </header>

      {tenantError && (
        <p role="alert" className="mx-6 mb-2 text-[13px] text-red-600 dark:text-red-400">
          {tenantError}
        </p>
      )}
      {error && (
        <p role="alert" className="mx-6 mb-2 text-[13px] text-red-600 dark:text-red-400">
          {error}
        </p>
      )}

      <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6 sm:px-8">
        <div className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900">
          <div className="flex h-12 items-center gap-3 bg-neutral-50 px-4 dark:bg-neutral-800/40">
            <h3 className="min-w-0 flex-1 truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
              Recent runs
            </h3>
            <span className="shrink-0 text-xs tabular-nums text-neutral-500">{runs.length}</span>
          </div>
          <ul className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
            {runs.map((r) => (
              <li key={r.id} className="flex items-center gap-3 px-4 py-2.5">
                <span
                  aria-hidden
                  className={cx("h-1.5 w-1.5 shrink-0 rounded-full", STATUS_DOT[r.status] ?? STATUS_DOT.running)}
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                    {r.workflow} · {r.subjectType}/{r.subjectId.slice(0, 12)}
                  </span>
                  <span className="block truncate text-xs text-neutral-500">
                    {r.error ??
                      ([r.models?.drafter, r.models?.judge].filter(Boolean).join(" + ") || "—")}
                  </span>
                </span>
                <span className="shrink-0 text-[13px] tabular-nums text-neutral-600 dark:text-neutral-400">
                  {r.status}
                </span>
              </li>
            ))}
            {runs.length === 0 && loaded && !loading && (
              <li className="px-4 py-10 text-center text-[13px] text-neutral-400 dark:text-neutral-600">
                No agent runs yet — workflows appear here when triggered.
              </li>
            )}
            {runs.length === 0 && (!loaded || tenantsLoading) && (
              <li className="px-4 py-10 text-center text-[13px] text-neutral-400 dark:text-neutral-600">
                Loading…
              </li>
            )}
          </ul>
        </div>
      </div>
    </div>
  );
}
