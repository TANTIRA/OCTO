"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, RefreshCw } from "lucide-react";
import { getJson, messageFor, postJson } from "@/lib/api";
import { TenantPicker, useTenants } from "@/lib/use-tenants";

/**
 * Reconciliation, live: `POST /api/v1/reconciliations` runs the match against
 * the IBOR and returns RunView — matched count plus BreakViews, each already
 * carrying its review task. Records come from the operator here (source
 * adapters land later); the run itself is fully server-side.
 */

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type Break = {
  kind: string;
  sourceSystem: string;
  sourceRef: string | null;
  detail: Record<string, string>;
  taskId: string;
  opened: boolean;
};

type Run = {
  runId: string;
  matched: number;
  breaks: Break[];
};

type RecordRow = {
  sourceSystem: string;
  externalId: string;
  amount: string;
  currency: string;
  date: string;
};

const emptyRow = (): RecordRow => ({
  sourceSystem: "manual",
  externalId: "",
  amount: "",
  currency: "USD",
  date: new Date().toISOString().slice(0, 10),
});

export default function ReconPanel() {
  const { tenants, tenantId, setTenantId, error: tenantError } = useTenants();
  const [rows, setRows] = useState<RecordRow[]>([emptyRow()]);
  const [run, setRun] = useState<Run | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const update = (i: number, patch: Partial<RecordRow>) =>
    setRows((prev) => prev.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));

  const submit = useCallback(async () => {
    if (!tenantId || running) return;
    setRunning(true);
    setError(null);
    setNotice(null);
    try {
      const result = await postJson<Run>("/api/v1/reconciliations", {
        tenantId,
        records: rows.map((r) => ({
          sourceSystem: r.sourceSystem,
          externalId: r.externalId,
          amount: r.amount,
          currency: r.currency,
          date: r.date,
        })),
      });
      setRun(result);
      setNotice(
        result.breaks.length === 0
          ? `all ${result.matched} records matched.`
          : `${result.matched} matched, ${result.breaks.length} break(s) — review tasks ${result.breaks.every((b) => b.opened) ? "opened" : "deduped to open ones"}.`,
      );
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setRunning(false);
    }
  }, [rows, running, tenantId]);

  return (
    <div className="flex h-full min-h-[680px] flex-col bg-white dark:bg-neutral-950">
      <header className="flex shrink-0 flex-wrap items-center gap-3 px-6 pt-6 pb-4 sm:px-8">
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-medium tracking-[-0.01em] text-neutral-900 dark:text-neutral-100">
            Reconciliation
          </h2>
          <p className="mt-0.5 text-[13px] text-neutral-500">
            Source records vs the IBOR. Breaks open review tasks automatically.
          </p>
        </div>
        <TenantPicker tenants={tenants} tenantId={tenantId} onChange={setTenantId} />
      </header>

      <div className="shrink-0 space-y-2 px-6 pb-3 sm:px-8">
        {rows.map((r, i) => (
          <div key={i} className="flex flex-wrap items-center gap-2">
            {(
              [
                ["sourceSystem", "Source", "text"],
                ["externalId", "External id", "text"],
                ["amount", "Amount", "number"],
                ["currency", "CCY", "text"],
                ["date", "Date", "date"],
              ] as const
            ).map(([field, label, type]) => (
              <input
                key={field}
                aria-label={label}
                type={type}
                step={type === "number" ? "0.01" : undefined}
                value={r[field]}
                placeholder={label}
                onChange={(e) => update(i, { [field]: e.target.value })}
                className="h-8 w-28 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
              />
            ))}
          </div>
        ))}
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setRows((p) => [...p, emptyRow()])}
            className="inline-flex h-8 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-3 text-[13px] font-medium text-neutral-700 hover:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700"
          >
            + record
          </button>
          <button
            type="button"
            disabled={running || !tenantId || rows.some((r) => !r.externalId || !r.amount)}
            onClick={submit}
            className="inline-flex h-8 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-3 text-[13px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] disabled:opacity-50 dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]"
          >
            {running ? <Loader2 aria-hidden className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" /> : "Run reconciliation"}
          </button>
        </div>
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
        <p className="mx-6 mb-2 text-[13px] text-emerald-600 dark:text-emerald-400">{notice}</p>
      )}

      {run && (
        <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6 sm:px-8">
          <div className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900">
            <div className="flex h-12 items-center gap-3 bg-neutral-50 px-4 dark:bg-neutral-800/40">
              <h3 className="min-w-0 flex-1 truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                Run {run.runId.slice(0, 8)}
              </h3>
              <span className="shrink-0 text-xs tabular-nums text-neutral-500">
                {run.matched} matched · {run.breaks.length} breaks
              </span>
            </div>
            <ul className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
              {run.breaks.map((b, i) => (
                <li key={`${b.taskId}-${i}`} className="flex items-start gap-3 px-4 py-2.5">
                  <span aria-hidden className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-red-500" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                      {b.kind} · {b.sourceSystem}
                    </span>
                    <span className="block truncate text-xs text-neutral-500">
                      {Object.entries(b.detail)
                        .map(([k, v]) => `${k}: ${v}`)
                        .join(" · ") || b.sourceRef || "—"}
                    </span>
                  </span>
                  <span
                    className={cx(
                      "shrink-0 rounded-[var(--rb-r-xs,4px)] px-1.5 py-0.5 text-[11px] font-medium",
                      b.opened
                        ? "bg-amber-100 text-amber-700 dark:bg-amber-500/10 dark:text-amber-400"
                        : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
                    )}
                  >
                    {b.opened ? "task opened" : "existing task"}
                  </span>
                </li>
              ))}
              {run.breaks.length === 0 && (
                <li className="px-4 py-10 text-center text-[13px] text-emerald-600 dark:text-emerald-400">
                  Clean run — no breaks.
                </li>
              )}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
}
