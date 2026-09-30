"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, RefreshCw, ShieldCheck } from "lucide-react";
import { getJson, messageFor, postJson } from "@/lib/api";
import { useTenants } from "@/lib/use-tenants";

/**
 * Report queue, live and honest to the engine contract (backlog #13/#35).
 *
 * The runner only executes `performance` (positionSourceType `inline-series`)
 * and `gl-export` (`inline-events`) today; exposure/attribution throw
 * "not supported yet". A performance job needs a real investor-signed cash-
 * flow series (§10.2) supplied inline — so this form collects one from the
 * user rather than posting a fabricated `positionSourceType:"fund"` with a
 * measure name (`netIrr`) the engine does not produce.
 *
 * Submitted jobs are polled through `GET /api/v1/reports/{id}` until they
 * reach a terminal status; a `done` job can then open the release gate with
 * `POST /api/v1/reports/{id}/release`. The artifact stays sealed until the
 * approval task is approved — the UI shows `released` from the server.
 */

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type Job = {
  id: string;
  type: string;
  status: "new" | "executing" | "done" | "error";
  measures: string[];
  result: unknown;
  error: string | null;
  artifactSha256: string | null;
  createdAt: string;
};

// Only the measures the performance engine actually returns (ReportRunner.kt).
const PERFORMANCE_MEASURES = ["dpi", "rvpi", "tvpi", "irr"] as const;

const STATUS_DOT: Record<Job["status"], string> = {
  new: "bg-neutral-300 dark:bg-neutral-600",
  executing: "bg-amber-500",
  done: "bg-emerald-500",
  error: "bg-red-500",
};

type Flow = { date: string; amount: string };

const today = () => new Date().toISOString().slice(0, 10);

export default function ReportQueue() {
  const { tenantId } = useTenants();
  const [jobs, setJobs] = useState<Job[]>([]);
  const [polling, setPolling] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Inline-series inputs — the real evidence base for a performance report.
  const [currency, setCurrency] = useState("USD");
  const [nav, setNav] = useState("120");
  const [valuationDate, setValuationDate] = useState(today());
  const [flows, setFlows] = useState<Flow[]>([
    { date: "2024-01-15", amount: "-100" },
    { date: "2025-06-30", amount: "30" },
  ]);
  const [measures, setMeasures] = useState<string[]>(["tvpi", "dpi", "irr"]);

  const refresh = useCallback(async () => {
    const active = jobs.filter((j) => j.status === "new" || j.status === "executing");
    if (active.length === 0) return;
    setPolling(true);
    try {
      const updated = await Promise.all(
        active.map((j) => getJson<Job>(`/api/v1/reports/${j.id}`).catch(() => j)),
      );
      setJobs((prev) => prev.map((j) => updated.find((u) => u.id === j.id) ?? j));
    } finally {
      setPolling(false);
    }
  }, [jobs]);

  // Poll while any job is still running; stop once all are terminal.
  useEffect(() => {
    if (!jobs.some((j) => j.status === "new" || j.status === "executing")) return;
    const t = window.setTimeout(refresh, 3000);
    return () => window.clearTimeout(t);
  }, [jobs, refresh]);

  const toggleMeasure = (m: string) =>
    setMeasures((prev) => (prev.includes(m) ? prev.filter((x) => x !== m) : [...prev, m]));

  const submitPerformance = async () => {
    if (!tenantId || submitting) return;
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      const job = await postJson<Job>("/api/v1/reports", {
        tenantId,
        type: "performance",
        positionSourceType: "inline-series",
        positionSourceId: `perf-${valuationDate}`,
        measures,
        parameters: {
          currency,
          valuationDate,
          nav,
          flows: flows.map((f) => ({ date: f.date, amount: f.amount })),
        },
      });
      setJobs((prev) => [job, ...prev]);
      setNotice("performance job queued — polling for the result.");
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setSubmitting(false);
    }
  };

  const release = async (id: string) => {
    setError(null);
    setNotice(null);
    try {
      const view = await postJson<{ released: boolean; taskStatus: string | null }>(
        `/api/v1/reports/${id}/release`,
        {},
      );
      setNotice(
        view.released
          ? "released — approval granted, artifact unsealed."
          : `approval task opened (${view.taskStatus ?? "pending"}) — the artifact stays sealed until a human approves.`,
      );
    } catch (e) {
      setError(messageFor(e));
    }
  };

  return (
    <div className="flex h-full min-h-[680px] flex-col bg-white dark:bg-neutral-950">
      <header className="flex shrink-0 flex-wrap items-center gap-3 px-6 pt-6 pb-4 sm:px-8">
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-medium tracking-[-0.01em] text-neutral-900 dark:text-neutral-100">
            Reports
          </h2>
          <p className="mt-0.5 text-[13px] text-neutral-500">
            Performance runs over the investor-signed series you supply; every
            outbound artifact passes the approval gate.
          </p>
        </div>
        <button
          type="button"
          onClick={refresh}
          aria-label="Refresh jobs"
          className="inline-flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] border border-neutral-200/70 text-neutral-600 hover:bg-neutral-50 dark:border-neutral-800 dark:text-neutral-400 dark:hover:bg-neutral-900"
        >
          <RefreshCw
            aria-hidden
            className={cx("h-4 w-4", polling && "animate-spin motion-reduce:animate-none")}
          />
        </button>
      </header>

      {error && (
        <p role="alert" className="mx-6 mb-2 text-[13px] text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
      {notice && (
        <p className="mx-6 mb-2 text-[13px] text-emerald-600 dark:text-emerald-400">
          {notice}
        </p>
      )}

      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6 sm:px-8">
        <section className="rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white p-4 dark:border-neutral-800 dark:bg-neutral-900">
          <h3 className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            New performance report
          </h3>
          <div className="mt-3 flex flex-wrap items-end gap-2">
            <label className="flex flex-col text-[11px] text-neutral-500">
              Currency
              <input
                value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                className="mt-0.5 h-8 w-20 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
              />
            </label>
            <label className="flex flex-col text-[11px] text-neutral-500">
              NAV
              <input
                type="number"
                value={nav}
                onChange={(e) => setNav(e.target.value)}
                className="mt-0.5 h-8 w-24 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
              />
            </label>
            <label className="flex flex-col text-[11px] text-neutral-500">
              Valuation date
              <input
                type="date"
                value={valuationDate}
                onChange={(e) => setValuationDate(e.target.value)}
                className="mt-0.5 h-8 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
              />
            </label>
          </div>

          <p className="mt-3 text-[11px] text-neutral-500">
            Cash flows — investor-signed (contributions negative, distributions
            positive, §10.2)
          </p>
          <div className="mt-1 space-y-1.5">
            {flows.map((f, i) => (
              <div key={i} className="flex items-center gap-2">
                <input
                  type="date"
                  aria-label={`Flow ${i + 1} date`}
                  value={f.date}
                  onChange={(e) =>
                    setFlows((prev) => prev.map((x, idx) => (idx === i ? { ...x, date: e.target.value } : x)))
                  }
                  className="h-8 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
                />
                <input
                  type="number"
                  step="0.01"
                  aria-label={`Flow ${i + 1} amount`}
                  value={f.amount}
                  onChange={(e) =>
                    setFlows((prev) => prev.map((x, idx) => (idx === i ? { ...x, amount: e.target.value } : x)))
                  }
                  className="h-8 w-28 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
                />
                {flows.length > 1 && (
                  <button
                    type="button"
                    onClick={() => setFlows((prev) => prev.filter((_, idx) => idx !== i))}
                    aria-label={`Remove flow ${i + 1}`}
                    className="text-[13px] text-neutral-400 hover:text-red-500"
                  >
                    ×
                  </button>
                )}
              </div>
            ))}
            <button
              type="button"
              onClick={() => setFlows((prev) => [...prev, { date: today(), amount: "0" }])}
              className="inline-flex h-7 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-2.5 text-[12px] font-medium text-neutral-700 hover:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700"
            >
              + flow
            </button>
          </div>

          <p className="mt-3 text-[11px] text-neutral-500">Measures</p>
          <div className="mt-1 flex flex-wrap gap-1.5">
            {PERFORMANCE_MEASURES.map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => toggleMeasure(m)}
                className={cx(
                  "inline-flex h-7 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] px-2.5 text-[12px] font-medium",
                  measures.includes(m)
                    ? "bg-[var(--rb-accent,oklch(20.5%_0_0))] text-[var(--rb-accent-fg,oklch(100%_0_0))] dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]"
                    : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
                )}
              >
                {m}
              </button>
            ))}
          </div>

          <button
            type="button"
            disabled={submitting || !tenantId || flows.some((f) => !f.date || f.amount === "")}
            onClick={submitPerformance}
            className="mt-4 inline-flex h-9 cursor-pointer items-center rounded-[var(--rb-r-md,8px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-4 text-[13px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] disabled:opacity-50 dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]"
          >
            {submitting ? (
              <Loader2 aria-hidden className="h-4 w-4 animate-spin motion-reduce:animate-none" />
            ) : (
              "Queue report"
            )}
          </button>
        </section>

        <section className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900">
          <div className="flex h-12 items-center gap-3 bg-neutral-50 px-4 dark:bg-neutral-800/40">
            <h3 className="min-w-0 flex-1 truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
              Jobs this session
            </h3>
            <span className="shrink-0 text-xs tabular-nums text-neutral-500">{jobs.length}</span>
          </div>
          <ul className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
            {jobs.map((j) => (
              <li key={j.id} className="flex items-center gap-3 px-4 py-2.5">
                <span
                  aria-hidden
                  className={cx("h-1.5 w-1.5 shrink-0 rounded-full", STATUS_DOT[j.status])}
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                    {j.type} · {j.id.slice(0, 8)}
                  </span>
                  <span className="block truncate text-xs text-neutral-500">
                    {j.error ?? (j.status === "done" ? "result ready (sealed until release)" : j.status)}
                  </span>
                </span>
                {j.status === "done" && (
                  <button
                    type="button"
                    onClick={() => release(j.id)}
                    className="inline-flex h-7 shrink-0 cursor-pointer items-center gap-1.5 rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-2 text-[12px] font-medium text-neutral-700 hover:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700"
                  >
                    <ShieldCheck aria-hidden className="h-3.5 w-3.5" />
                    Request release
                  </button>
                )}
                {j.status === "executing" && (
                  <Loader2
                    aria-hidden
                    className="h-4 w-4 shrink-0 animate-spin text-neutral-400 motion-reduce:animate-none"
                  />
                )}
              </li>
            ))}
            {jobs.length === 0 && (
              <li className="px-4 py-10 text-center text-[13px] text-neutral-400 dark:text-neutral-600">
                No jobs yet — queue one above.
              </li>
            )}
          </ul>
        </section>
      </div>
    </div>
  );
}
