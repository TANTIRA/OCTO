"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, ShieldCheck } from "lucide-react";
import PanelHeader from "@/components/panel-header";
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
 * `POST /api/v1/reports/{id}/release`. The gate's `taskStatus` comes back on
 * the same payload, so a job keeps polling while its approval is open and
 * shows `released` plus the artifact once an approver decides (#489/#490).
 * An approver also receives the draft before that decision (#552); the queue
 * shows whatever body the API returned, and `released` stays the gate.
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
  released: boolean;
  approvalTaskId: string | null;
  taskStatus: string | null;
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

const isAmount = (v: string) => v.trim() !== "" && Number.isFinite(Number(v));

// A done job with an undecided release task still has a live outcome — the
// queue keeps it polling so the approver's decision lands without a refresh.
const gatePending = (j: Job) =>
  j.status === "done" && (j.taskStatus === "open" || j.taskStatus === "in_rework");

// What the row shows under the job name — the release gate's verdict is part
// of the job's state, not a separate concern the queue has to infer (#490).
const doneDetail = (j: Job): string => {
  if (j.status !== "done") return j.status;
  if (j.released) return "released — artifact unsealed";
  if (j.taskStatus === "rejected" || j.taskStatus === "cancelled")
    return `release ${j.taskStatus} — artifact stays unreleased`;
  if (j.result != null) return "draft — pending release";
  if (j.approvalTaskId) return "awaiting approval — sealed until the gate decides";
  return "result ready (sealed until release)";
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// The API has no job list to re-read, so this tab remembers the ids it queued
// per workspace. Leaving the area no longer strands a sealed report.
function storedJobIds(key: string): string[] {
  try {
    const ids: unknown = JSON.parse(sessionStorage.getItem(key) ?? "[]");
    return Array.isArray(ids)
      ? ids.filter((id): id is string => typeof id === "string" && UUID.test(id))
      : [];
  } catch {
    return [];
  }
}

export default function ReportQueue() {
  const { tenantId } = useTenants();
  const [jobs, setJobs] = useState<Job[]>([]);
  const [polling, setPolling] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [restoring, setRestoring] = useState(false);

  // Inline-series inputs — the real evidence base for a performance report.
  // Empty until the user supplies them — a pre-filled currency, NAV, valuation
  // date or cash-flow series would let a report run on values nobody entered
  // (#314, #333).
  const [currency, setCurrency] = useState("");
  const [nav, setNav] = useState("");
  const [valuationDate, setValuationDate] = useState("");
  const [flows, setFlows] = useState<Flow[]>([{ date: "", amount: "" }]);
  const [measures, setMeasures] = useState<string[]>(["tvpi", "dpi", "irr"]);

  const formValid =
    /^[A-Z]{3}$/.test(currency) &&
    isAmount(nav) &&
    Number(nav) >= 0 &&
    valuationDate !== "" &&
    measures.length > 0 &&
    flows.length > 0 &&
    flows.every((f) => f.date !== "" && isAmount(f.amount));
  const jobsKey = `octo.report-jobs.${tenantId}`;

  useEffect(() => {
    const ids = storedJobIds(jobsKey);
    if (ids.length === 0) return;
    let cancelled = false;
    let failure: unknown = null;
    setRestoring(true);
    Promise.all(
      ids.map((id) =>
        getJson<Job>(`/api/v1/reports/${id}`).catch((e) => {
          failure = e;
          return null;
        }),
      ),
    ).then(
      (list) => {
        if (cancelled) return;
        setRestoring(false);
        if (failure) setError(`Some jobs queued in this tab could not be re-read: ${messageFor(failure)}.`);
        // Merge: a job queued while this read was in flight stays on top.
        setJobs((prev) => [
          ...prev,
          ...list.filter((j): j is Job => j !== null && !prev.some((p) => p.id === j.id)),
        ]);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [jobsKey]);

  const refresh = useCallback(async () => {
    const active = jobs.filter((j) => j.status === "new" || j.status === "executing" || gatePending(j));
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

  // Poll while any job is still running or waiting on the release gate.
  useEffect(() => {
    if (!jobs.some((j) => j.status === "new" || j.status === "executing" || gatePending(j))) return;
    const t = window.setTimeout(refresh, 3000);
    return () => window.clearTimeout(t);
  }, [jobs, refresh]);

  const toggleMeasure = (m: string) =>
    setMeasures((prev) => (prev.includes(m) ? prev.filter((x) => x !== m) : [...prev, m]));

  const submitPerformance = async () => {
    if (!tenantId || submitting || !formValid) return;
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
      try {
        sessionStorage.setItem(jobsKey, JSON.stringify([job.id, ...storedJobIds(jobsKey)].slice(0, 20)));
      } catch {
        // Storage blocked: the job still shows until the area is left.
      }
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
      const view = await postJson<{
        approvalTaskId: string | null;
        taskStatus: string | null;
        released: boolean;
      }>(`/api/v1/reports/${id}/release`, {});
      setJobs((prev) =>
        prev.map((j) =>
          j.id === id
            ? {
                ...j,
                released: view.released,
                approvalTaskId: view.approvalTaskId,
                taskStatus: view.taskStatus,
              }
            : j,
        ),
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
    <div>
      <PanelHeader
        description="Performance runs over the investor-signed series you supply; every outbound artifact passes the approval gate."
        onRefresh={refresh}
        refreshing={polling}
        refreshLabel="Refresh running jobs"
        error={error}
        notice={notice}
      />

      <div className="space-y-4">
        <section className="rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white p-4 dark:border-neutral-800 dark:bg-neutral-900">
          <h3 className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
            New performance report
          </h3>
          <div className="mt-3 flex flex-wrap items-end gap-2">
            <label className="flex flex-col text-[11px] text-neutral-500">
              Currency
              <input
                value={currency}
                placeholder="USD"
                maxLength={3}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                className="mt-0.5 h-8 w-20 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] text-neutral-900 dark:border-neutral-700 dark:bg-neutral-950 dark:text-neutral-100"
              />
            </label>
            <label className="flex flex-col text-[11px] text-neutral-500">
              NAV
              <input
                type="number"
                min="0"
                step="0.01"
                value={nav}
                placeholder="0.00"
                onChange={(e) => setNav(e.target.value)}
                className="mt-0.5 h-8 w-24 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] text-neutral-900 dark:border-neutral-700 dark:bg-neutral-950 dark:text-neutral-100"
              />
            </label>
            <label className="flex flex-col text-[11px] text-neutral-500">
              Valuation date
              <input
                type="date"
                value={valuationDate}
                onChange={(e) => setValuationDate(e.target.value)}
                className="mt-0.5 h-8 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] text-neutral-900 dark:border-neutral-700 dark:bg-neutral-950 dark:text-neutral-100"
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
                  className="h-8 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-700 dark:bg-neutral-950 dark:text-neutral-100"
                />
                <input
                  type="number"
                  step="0.01"
                  aria-label={`Flow ${i + 1} amount`}
                  value={f.amount}
                  onChange={(e) =>
                    setFlows((prev) => prev.map((x, idx) => (idx === i ? { ...x, amount: e.target.value } : x)))
                  }
                  className="h-8 w-28 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-700 dark:bg-neutral-950 dark:text-neutral-100"
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
              onClick={() => setFlows((prev) => [...prev, { date: "", amount: "" }])}
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
            disabled={submitting || !tenantId || !formValid}
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
                    {j.error ?? doneDetail(j)}
                  </span>
                  {j.artifactSha256 && (
                    <span className="block truncate font-mono text-[11px] text-neutral-400 dark:text-neutral-500">
                      sha256 {j.artifactSha256.slice(0, 16)}
                    </span>
                  )}
                  {j.result != null && (
                    <pre
                      aria-label={j.released ? "Released report" : "Report draft"}
                      className="mt-1 max-h-40 overflow-auto rounded-[var(--rb-r-sm,6px)] bg-neutral-50 p-2 text-[11px] text-neutral-600 dark:bg-neutral-950 dark:text-neutral-400"
                    >
                      {JSON.stringify(j.result, null, 2)}
                    </pre>
                  )}
                </span>
                {j.status === "done" && j.approvalTaskId === null && (
                  <button
                    type="button"
                    onClick={() => release(j.id)}
                    className="inline-flex h-7 shrink-0 cursor-pointer items-center gap-1.5 rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-2 text-[12px] font-medium text-neutral-700 hover:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700"
                  >
                    <ShieldCheck aria-hidden className="h-3.5 w-3.5" />
                    Request release
                  </button>
                )}
                {(j.status === "executing" || gatePending(j)) && (
                  <Loader2
                    aria-hidden
                    className="h-4 w-4 shrink-0 animate-spin text-neutral-400 motion-reduce:animate-none"
                  />
                )}
              </li>
            ))}
            {jobs.length === 0 && (
              <li className="px-4 py-10 text-center text-[13px] text-neutral-400 dark:text-neutral-600">
                {restoring ? "Loading…" : "No jobs yet — queue one above."}
              </li>
            )}
          </ul>
        </section>
      </div>
    </div>
  );
}
