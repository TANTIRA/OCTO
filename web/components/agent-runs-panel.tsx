"use client";

import { useCallback, useEffect, useState } from "react";
import PanelHeader from "@/components/panel-header";
import { getJson, messageFor } from "@/lib/api";
import { useTenants } from "@/lib/use-tenants";

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
  const { tenantId } = useTenants();
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
    <div>
      <PanelHeader
        description="Every agent workflow run, with its models and judge verdict — the audit spine, read-only."
        onRefresh={load}
        refreshing={loading}
        refreshLabel="Refresh runs"
        error={error}
      />

      <div>
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
            {runs.length === 0 && !loaded && !error && (
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
