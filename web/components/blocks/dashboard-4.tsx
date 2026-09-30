"use client";

import { useCallback, useEffect, useState } from "react";

import PanelHeader from "@/components/panel-header";
import { getJson, messageFor } from "@/lib/api";
import { useTenants } from "@/lib/use-tenants";

/**
 * Portfolio overview, live: every figure below is a real read —
 *   GET /api/v1/prospects?tenantId&stage      (pipeline, per stage)
 *   GET /api/v1/agent-runs?tenantId&limit=50  (audit spine)
 *   GET /api/v1/compliance/rules?tenantId     (post-trade rules)
 *   GET /api/v1/report-schedules?tenantId     (LP cadence)
 * NAV/TVPI/IRR aren't served by the API yet, so this surface shows pipeline
 * and operations health instead of fabricated performance numbers (#314).
 */

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type Prospect = {
  id: string;
  name: string;
  stage: string;
  lastEventAt: string;
};

type Run = {
  id: string;
  workflow: string;
  status: string;
  createdAt?: string;
  error?: string | null;
};

const STAGES = [
  { id: "sourced", name: "Sourced" },
  { id: "screening", name: "Screening" },
  { id: "due-diligence", name: "Due diligence" },
  { id: "ic-review", name: "IC review" },
] as const;

// Per-stage read cap: a full page means "at least this many", never an exact count.
const LIMIT = 200;

const STAGE_NAME: Record<string, string> = Object.fromEntries(
  STAGES.map((s) => [s.id, s.name]),
);

const RUN_DOT: Record<string, string> = {
  running: "bg-amber-500",
  completed: "bg-emerald-500",
  refused: "bg-red-500",
  failed: "bg-red-500",
};

const fmtTime = (iso?: string) =>
  iso
    ? new Date(iso).toLocaleString(undefined, {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "";

export default function Dashboard4() {
  const body = useScrollFade<HTMLDivElement>();
  const { tenantId } = useTenants();

  const [prospects, setProspects] = useState<Record<string, Prospect[]>>({});
  const [runs, setRuns] = useState<Run[]>([]);
  const [rules, setRules] = useState<unknown[]>([]);
  const [schedules, setSchedules] = useState<unknown[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true);
    setError(null);
    try {
      const [perStage, runList, ruleList, scheduleList] = await Promise.all([
        Promise.all(
          STAGES.map(async (s) => {
            const list = await getJson<Prospect[]>(
              `/api/v1/prospects?tenantId=${tenantId}&stage=${s.id}&limit=${LIMIT}`,
            );
            return [s.id, list] as const;
          }),
        ),
        getJson<Run[]>(`/api/v1/agent-runs?tenantId=${tenantId}&limit=50`),
        getJson<unknown[]>(`/api/v1/compliance/rules?tenantId=${tenantId}`),
        getJson<unknown[]>(`/api/v1/report-schedules?tenantId=${tenantId}`),
      ]);
      setProspects(Object.fromEntries(perStage));
      setRuns(runList);
      setRules(ruleList);
      setSchedules(scheduleList);
      setLoadedAt(new Date());
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setLoading(false);
    }
  }, [tenantId]);

  useEffect(() => {
    load();
  }, [load]);

  const pipelineCount = STAGES.reduce(
    (n, s) => n + (prospects[s.id]?.length ?? 0),
    0,
  );
  const capped = STAGES.some((s) => prospects[s.id]?.length === LIMIT);
  const attention = runs.filter(
    (r) => r.status === "failed" || r.status === "refused",
  );
  const recentProspects = STAGES.flatMap((s) => prospects[s.id] ?? [])
    .sort((a, b) => b.lastEventAt.localeCompare(a.lastEventAt))
    .slice(0, 8);

  const stats = [
    { label: "Prospects in pipeline", value: `${pipelineCount}${capped ? "+" : ""}` },
    { label: "Agent runs (latest 50)", value: String(runs.length) },
    { label: "Compliance rules", value: String(rules.length) },
    { label: "Report schedules", value: String(schedules.length) },
  ];

  // Until the first read lands (or when it failed) a table has no honest
  // empty state — say which, instead of "none yet" or a fabricated 0.
  const placeholder = error ? "Unavailable" : "Loading…";

  return (
    <div>
      <PanelHeader
        description={
          attention.length > 0 ? (
            <span className="inline-flex items-center gap-1.5 text-neutral-600 dark:text-neutral-400">
              <span aria-hidden className="h-1.5 w-1.5 shrink-0 rounded-full bg-red-500" />
              {attention.length} agent run{attention.length === 1 ? "" : "s"} need
              {attention.length === 1 ? "s" : ""} attention
            </span>
          ) : (
            "Pipeline and operations health for this workspace."
          )
        }
        onRefresh={load}
        refreshing={loading}
        refreshLabel="Refresh overview"
        error={error}
      >
        <span aria-live="polite" className="hidden text-[13px] tabular-nums text-neutral-500 sm:inline">
          {loadedAt
            ? `Updated ${loadedAt.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}`
            : ""}
        </span>
      </PanelHeader>

      <div className="grid grid-cols-2 gap-1 rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-neutral-50 p-1 lg:grid-cols-4 dark:border-neutral-800 dark:bg-neutral-950">
        {stats.map((s) => (
          <div
            key={s.label}
            className="rounded-[var(--rb-r-lg,10px)] border border-neutral-200/70 bg-white p-4 dark:border-neutral-800 dark:bg-neutral-900"
          >
            <p className="truncate text-[13px] text-neutral-500">
              {s.label}
            </p>
            <p className="mt-2 truncate text-2xl font-medium tabular-nums tracking-[-0.02em] text-neutral-900 dark:text-neutral-100">
              {loadedAt ? s.value : "—"}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-3">
          <span
            aria-live="polite"
            className="hidden text-[13px] tabular-nums text-neutral-500 sm:inline"
          >
            {loading
              ? "Refreshing…"
              : loadedAt
                ? `Updated ${loadedAt.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}`
                : ""}
          </span>
          <button
            type="button"
            onClick={load}
            disabled={loading || !tenantId}
            className="inline-flex h-9 cursor-pointer items-center gap-2 rounded-[var(--rb-r-md,8px)] border border-neutral-200 bg-white px-3 text-sm font-medium text-neutral-900 transition-[transform,background-color,border-color,color] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] hover:border-neutral-300 hover:bg-neutral-50 active:scale-[0.97] focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] disabled:pointer-events-none disabled:opacity-50 dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100 dark:hover:border-neutral-700 dark:hover:bg-neutral-800 dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]"
          >
            <RefreshCw
              aria-hidden
              className={cx(
                "h-4 w-4 shrink-0 text-neutral-500",
                loading && "animate-spin motion-reduce:animate-none",
              )}
            />
            Refresh
          </button>
        </div>
      </header>

      <div className="relative min-h-0 flex-1">
        <div
          ref={body.ref}
          onScroll={body.onScroll}
          className="h-full overflow-y-auto p-4 sm:p-6"
        >
          {error && (
            <p className="mb-4 rounded-[var(--rb-r-lg,10px)] border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300">
              {error}
            </p>
          )}

          <div className="grid grid-cols-2 gap-1 rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-neutral-50 p-1 lg:grid-cols-4 dark:border-neutral-800 dark:bg-neutral-950">
            {stats.map((s) => (
              <div
                key={s.label}
                className="rounded-[var(--rb-r-lg,10px)] border border-neutral-200/70 bg-white p-4 dark:border-neutral-800 dark:bg-neutral-900"
              >
                <p className="truncate text-[13px] text-neutral-500">
                  {s.label}
                </p>
                <p className="mt-2 truncate text-2xl font-medium tabular-nums tracking-[-0.02em] text-neutral-900 dark:text-neutral-100">
                  {loadedAt ? s.value : "—"}
                </p>
              </div>
            ))}
          </div>
        ))}
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900">
          <div className="flex h-12 items-center justify-between bg-neutral-50 px-4 dark:bg-neutral-900/60">
            <h2 className="text-sm font-medium text-neutral-900 dark:text-neutral-100">
              Pipeline
            </h2>
            <span className="flex items-center gap-3 text-[11px] tabular-nums text-neutral-500">
              {STAGES.map((s) => (
                <span key={s.id}>
                  {s.name}{" "}
                  <span className="font-medium text-neutral-700 dark:text-neutral-300">
                    {!loadedAt
                      ? "—"
                      : `${prospects[s.id].length}${prospects[s.id].length === LIMIT ? "+" : ""}`}
                  </span>
                </span>
              ))}
            </span>
          </div>
          <table className="w-full border-collapse text-left">
            <caption className="sr-only">
              Recently active prospects
            </caption>
            <thead>
              <tr>
                <th
                  scope="col"
                  className="h-9 px-3 text-xs font-medium text-neutral-500 first:pl-4"
                >
                  Prospect
                </th>
                <th
                  scope="col"
                  className="hidden h-9 px-3 text-right text-xs font-medium text-neutral-500 sm:table-cell"
                >
                  Stage
                </th>
                <th
                  scope="col"
                  className="h-9 px-3 text-right text-xs font-medium text-neutral-500 last:pr-4"
                >
                  Last event
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
              {recentProspects.length === 0 && (
                <tr>
                  <td
                    colSpan={3}
                    className="px-4 py-6 text-center text-[13px] text-neutral-500"
                  >
                    {loadedAt
                      ? "No prospects yet — register one from the deal pipeline."
                      : placeholder}
                  </td>
                </tr>
              )}
              {recentProspects.map((p) => (
                <tr
                  key={p.id}
                  className="h-11 transition-colors duration-150 hover:bg-neutral-50 dark:hover:bg-neutral-800/50"
                >
                  <td className="min-w-0 px-3 first:pl-4">
                    <p className="truncate text-[13px] text-neutral-900 dark:text-neutral-100">
                      {p.name}
                    </p>
                  </td>
                  <td className="hidden px-3 text-right text-[13px] text-neutral-600 sm:table-cell dark:text-neutral-400">
                    {STAGE_NAME[p.stage] ?? p.stage}
                  </td>
                  <td className="px-3 text-right text-[13px] tabular-nums text-neutral-600 last:pr-4 dark:text-neutral-400">
                    {fmtTime(p.lastEventAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900">
          <div className="flex h-12 items-center justify-between bg-neutral-50 px-4 dark:bg-neutral-900/60">
            <h2 className="text-sm font-medium text-neutral-900 dark:text-neutral-100">
              Agent runs needing attention
            </h2>
            <span className="inline-flex h-5 shrink-0 items-center rounded-[var(--rb-r-xs,4px)] bg-neutral-200/70 px-1.5 text-[11px] font-medium tabular-nums text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400">
              {loadedAt ? attention.length : "—"}
            </span>
          </div>
          <ul className="flex flex-col gap-1.5 p-1.5">
            {attention.length === 0 && (
              <li className="px-3 py-6 text-center text-[13px] text-neutral-500">
                {loadedAt
                  ? "No failed or refused runs in the latest 50."
                  : placeholder}
              </li>
            )}
            {attention.slice(0, 8).map((r) => (
              <li
                key={r.id}
                className="flex min-h-11 items-start gap-2.5 rounded-[var(--rb-r-lg,10px)] bg-neutral-50 px-3 py-2.5 dark:bg-neutral-800/50"
              >
                <span
                  className={cx(
                    "mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full",
                    RUN_DOT[r.status] ??
                      "bg-neutral-300 dark:bg-neutral-600",
                  )}
                />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13px] text-neutral-900 dark:text-neutral-100">
                    {r.workflow}
                  </p>
                  <p className="mt-0.5 truncate text-xs text-neutral-500">
                    {r.status}
                    {r.error ? ` — ${r.error}` : ""} ·{" "}
                    {fmtTime(r.createdAt)}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
