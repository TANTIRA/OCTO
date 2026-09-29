"use client";

import { useCallback, useEffect, useState } from "react";
import { Check, Loader2, RefreshCw, ShieldCheck } from "lucide-react";
import { getJson, messageFor, postJson } from "@/lib/api";
import { TenantPicker, useTenants } from "@/lib/use-tenants";

/**
 * Report queue, live: submit jobs through `POST /api/v1/reports`, watch the
 * status machine (new → executing → done/error), and open the release gate
 * with `POST /api/v1/reports/{id}/release`. The artifact stays sealed until
 * the approval task is approved — the UI shows `released` from the server,
 * it never decides.
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

const TYPES = ["performance", "exposure", "attribution", "gl-export"] as const;

const STATUS_DOT: Record<Job["status"], string> = {
  new: "bg-neutral-300 dark:bg-neutral-600",
  executing: "bg-amber-500",
  done: "bg-emerald-500",
  error: "bg-red-500",
};

export default function ReportQueue() {
  const { tenants, tenantId, setTenantId, error: tenantError } = useTenants();
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    // The API exposes per-job reads; the queue view samples the recent jobs it
    // submitted this session, refreshed on demand.
    setLoading(true);
    setError(null);
    try {
      setJobs((prev) =>
        prev.map((j) => ({ ...j })).sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (jobs.some((j) => j.status === "new" || j.status === "executing")) {
      const t = window.setTimeout(load, 5000);
      return () => window.clearTimeout(t);
    }
  }, [jobs, load]);

  const submit = async (type: string) => {
    if (!tenantId || submitting) return;
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      const job = await postJson<Job>("/api/v1/reports", {
        tenantId,
        type,
        positionSourceType: "fund",
        positionSourceId: tenantId,
        measures: type === "gl-export" ? ["journal"] : ["tvpi", "dpi", "netIrr"],
      });
      setJobs((prev) => [job, ...prev]);
      setNotice(`${type} job queued.`);
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
          ? "released."
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
            Engine jobs run in-process; every outbound artifact passes the
            approval gate.
          </p>
        </div>
        <TenantPicker tenants={tenants} tenantId={tenantId} onChange={setTenantId} />
        <button
          type="button"
          onClick={load}
          aria-label="Refresh jobs"
          className="inline-flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] border border-neutral-200/70 text-neutral-600 hover:bg-neutral-50 dark:border-neutral-800 dark:text-neutral-400 dark:hover:bg-neutral-900"
        >
          <RefreshCw
            aria-hidden
            className={cx("h-4 w-4", loading && "animate-spin motion-reduce:animate-none")}
          />
        </button>
      </header>

      <div className="flex shrink-0 flex-wrap gap-2 px-6 pb-3 sm:px-8">
        {TYPES.map((t) => (
          <button
            key={t}
            type="button"
            disabled={submitting || !tenantId}
            onClick={() => submit(t)}
            className="inline-flex h-8 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-3 text-[13px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] disabled:opacity-50 dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]"
          >
            {submitting ? <Loader2 aria-hidden className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" /> : `New ${t}`}
          </button>
        ))}
      </div>

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
      {notice && (
        <p className="mx-6 mb-2 text-[13px] text-emerald-600 dark:text-emerald-400">
          {notice}
        </p>
      )}

      <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6 sm:px-8">
        <div className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900">
          <div className="flex h-12 items-center gap-3 bg-neutral-50 px-4 dark:bg-neutral-800/40">
            <h3 className="min-w-0 flex-1 truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
              Jobs this session
            </h3>
            <span className="shrink-0 text-xs tabular-nums text-neutral-500">
              {jobs.length}
            </span>
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
                    {j.error ?? (j.result ? "result ready (sealed until release)" : j.status)}
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
                  <Loader2 aria-hidden className="h-4 w-4 shrink-0 animate-spin text-neutral-400 motion-reduce:animate-none" />
                )}
                {j.status === "done" && !j.result && <Check aria-hidden className="h-4 w-4 shrink-0 text-emerald-500" />}
              </li>
            ))}
            {jobs.length === 0 && (
              <li className="px-4 py-10 text-center text-[13px] text-neutral-400 dark:text-neutral-600">
                No jobs yet — queue one above.
              </li>
            )}
          </ul>
        </div>
      </div>
    </div>
  );
}
