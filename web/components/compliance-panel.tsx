"use client";

import { useCallback, useState } from "react";
import { Loader2, RefreshCw, Scale } from "lucide-react";
import { getJson, messageFor, postJson } from "@/lib/api";
import { useTenants } from "@/lib/use-tenants";

/**
 * Post-trade compliance, live: `GET /api/v1/compliance/rules` lists the
 * tenant's active rules, `POST /api/v1/compliance/evaluations` runs the
 * engine over supplied inputs and returns OutcomeViews — breaches already
 * opened review tasks server-side. The narrative button calls
 * `POST /api/v1/compliance/rationale` (F9): the sidecar narrates and jev
 * citation-gates it; a refusal shows its note, not a fabricated rationale.
 */

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

type Rule = {
  ruleId: string;
  version: number;
  name: string;
  check: { type: string; maxFraction?: string; currency?: string; minRatio?: string };
};

type Outcome = {
  ruleId: string;
  version: number;
  result: string;
  measured: Record<string, string>;
  explanation: string;
  taskId: string | null;
  recorded: boolean;
};

type RationaleResult = {
  status: "completed" | "refused";
  rationale?: string;
  note?: string | null;
};

const RESULT_DOT: Record<string, string> = {
  pass: "bg-emerald-500",
  breach: "bg-red-500",
  "not-applicable": "bg-neutral-300 dark:bg-neutral-600",
};

export default function CompliancePanel() {
  const { tenantId } = useTenants();
  const [rules, setRules] = useState<Rule[]>([]);
  const [outcomes, setOutcomes] = useState<Outcome[]>([]);
  const [rationale, setRationale] = useState<RationaleResult | null>(null);
  const [loadingRules, setLoadingRules] = useState(false);
  const [running, setRunning] = useState(false);
  const [narrating, setNarrating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [asOf, setAsOf] = useState(() => new Date().toISOString().slice(0, 10));
  // The evaluation subject and currency-exposure are operator inputs, not
  // hardcoded — a live evaluation and its audit trail must carry real values
  // (backlog #35). Exposure rows are "CCY = fraction of the portfolio".
  const [subject, setSubject] = useState("");
  const [exposure, setExposure] = useState<{ currency: string; fraction: string }[]>([
    { currency: "USD", fraction: "1" },
  ]);

  const currencyExposure = (): Record<string, number> =>
    Object.fromEntries(
      exposure
        .filter((r) => r.currency.trim() && r.fraction.trim())
        .map((r) => [r.currency.toUpperCase(), Number(r.fraction)]),
    );

  const loadRules = useCallback(async () => {
    if (!tenantId) return;
    setLoadingRules(true);
    setError(null);
    try {
      setRules(await getJson<Rule[]>(`/api/v1/compliance/rules?tenantId=${tenantId}`));
      setOutcomes([]);
      setRationale(null);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setLoadingRules(false);
    }
  }, [tenantId]);

  const evaluate = async () => {
    if (!tenantId || running || !subject.trim()) return;
    setRunning(true);
    setError(null);
    setRationale(null);
    try {
      const list = await postJson<Outcome[]>("/api/v1/compliance/evaluations", {
        tenantId,
        subject: subject.trim(),
        asOf,
        currencyExposure: currencyExposure(),
      });
      setOutcomes(list);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setRunning(false);
    }
  };

  const narrate = async () => {
    if (!tenantId || narrating || !subject.trim()) return;
    setNarrating(true);
    setError(null);
    try {
      const res = await postJson<RationaleResult>("/api/v1/compliance/rationale", {
        tenantId,
        subject: subject.trim(),
        asOf,
        currencyExposure: currencyExposure(),
      });
      setRationale(res);
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setNarrating(false);
    }
  };

  return (
    <div className="flex h-full min-h-[680px] flex-col bg-white dark:bg-neutral-950">
      <header className="flex shrink-0 flex-wrap items-center gap-3 px-6 pt-6 pb-4 sm:px-8">
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-medium tracking-[-0.01em] text-neutral-900 dark:text-neutral-100">
            Compliance
          </h2>
          <p className="mt-0.5 text-[13px] text-neutral-500">
            Rules evaluate server-side; breaches open review tasks. Narration
            is AI-drafted and citation-gated.
          </p>
        </div>
        <button
          type="button"
          onClick={loadRules}
          aria-label="Load rules"
          className="inline-flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-[var(--rb-r-md,8px)] border border-neutral-200/70 text-neutral-600 hover:bg-neutral-50 dark:border-neutral-800 dark:text-neutral-400 dark:hover:bg-neutral-900"
        >
          <RefreshCw
            aria-hidden
            className={cx("h-4 w-4", loadingRules && "animate-spin motion-reduce:animate-none")}
          />
        </button>
      </header>

      <div className="flex shrink-0 flex-wrap items-center gap-2 px-6 pb-3 sm:px-8">
        <input
          aria-label="Subject"
          value={subject}
          placeholder="Subject (e.g. fund-II)"
          onChange={(e) => setSubject(e.target.value)}
          className="h-8 w-44 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
        />
        <label htmlFor="asof" className="text-[13px] text-neutral-600 dark:text-neutral-400">
          As of
        </label>
        <input
          id="asof"
          type="date"
          value={asOf}
          onChange={(e) => setAsOf(e.target.value)}
          className="h-8 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
        />
        {exposure.map((r, i) => (
          <span key={i} className="inline-flex items-center gap-1">
            <input
              aria-label={`Exposure ${i + 1} currency`}
              value={r.currency}
              placeholder="CCY"
              onChange={(e) =>
                setExposure((p) => p.map((x, idx) => (idx === i ? { ...x, currency: e.target.value.toUpperCase() } : x)))
              }
              className="h-8 w-16 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
            />
            <input
              aria-label={`Exposure ${i + 1} fraction`}
              type="number"
              step="0.01"
              value={r.fraction}
              onChange={(e) =>
                setExposure((p) => p.map((x, idx) => (idx === i ? { ...x, fraction: e.target.value } : x)))
              }
              className="h-8 w-20 rounded-[var(--rb-r-sm,6px)] border border-neutral-200/70 bg-white px-2 text-[13px] dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
            />
            {exposure.length > 1 && (
              <button
                type="button"
                onClick={() => setExposure((p) => p.filter((_, idx) => idx !== i))}
                aria-label={`Remove exposure ${i + 1}`}
                className="text-[13px] text-neutral-400 hover:text-red-500"
              >
                ×
              </button>
            )}
          </span>
        ))}
        <button
          type="button"
          onClick={() => setExposure((p) => [...p, { currency: "", fraction: "" }])}
          className="inline-flex h-8 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-2.5 text-[12px] font-medium text-neutral-700 hover:bg-neutral-200 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700"
        >
          + ccy
        </button>
        <button
          type="button"
          disabled={running || !tenantId || !subject.trim()}
          onClick={evaluate}
          className="inline-flex h-8 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-3 text-[13px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] disabled:opacity-50 dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))]"
        >
          {running ? <Loader2 aria-hidden className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" /> : "Evaluate"}
        </button>
        {outcomes.length > 0 && (
          <button
            type="button"
            disabled={narrating}
            onClick={narrate}
            className="inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-3 text-[13px] font-medium text-neutral-700 hover:bg-neutral-200 disabled:opacity-50 dark:bg-neutral-800 dark:text-neutral-300 dark:hover:bg-neutral-700"
          >
            <Scale aria-hidden className="h-3.5 w-3.5" />
            {narrating ? "Narrating…" : "AI rationale"}
          </button>
        )}
      </div>

      {error && (
        <p role="alert" className="mx-6 mb-2 text-[13px] text-red-600 dark:text-red-400">
          {error}
        </p>
      )}

      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6 sm:px-8">
        <section className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900">
          <div className="flex h-12 items-center gap-3 bg-neutral-50 px-4 dark:bg-neutral-800/40">
            <h3 className="min-w-0 flex-1 truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
              Active rules
            </h3>
            <span className="shrink-0 text-xs tabular-nums text-neutral-500">{rules.length}</span>
          </div>
          <ul className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
            {rules.map((r) => (
              <li key={r.ruleId} className="flex items-center gap-3 px-4 py-2.5">
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                    {r.name}
                  </span>
                  <span className="block truncate text-xs text-neutral-500">
                    {r.ruleId} · v{r.version} · {r.check.type}
                  </span>
                </span>
              </li>
            ))}
            {rules.length === 0 && !loadingRules && (
              <li className="px-4 py-10 text-center text-[13px] text-neutral-400 dark:text-neutral-600">
                No active rules — load them with the refresh button.
              </li>
            )}
          </ul>
        </section>

        {outcomes.length > 0 && (
          <section className="overflow-hidden rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white dark:border-neutral-800 dark:bg-neutral-900">
            <div className="flex h-12 items-center gap-3 bg-neutral-50 px-4 dark:bg-neutral-800/40">
              <h3 className="min-w-0 flex-1 truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                Outcomes · {asOf}
              </h3>
            </div>
            <ul className="divide-y divide-neutral-100 dark:divide-neutral-800/70">
              {outcomes.map((o) => (
                <li key={`${o.ruleId}-v${o.version}`} className="flex items-start gap-3 px-4 py-2.5">
                  <span
                    aria-hidden
                    className={cx("mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full", RESULT_DOT[o.result] ?? RESULT_DOT["not-applicable"])}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                      {o.ruleId} — {o.result}
                    </span>
                    <span className="block truncate text-xs text-neutral-500">{o.explanation}</span>
                  </span>
                  {o.taskId && (
                    <span className="shrink-0 rounded-[var(--rb-r-xs,4px)] bg-amber-100 px-1.5 py-0.5 text-[11px] font-medium text-amber-700 dark:bg-amber-500/10 dark:text-amber-400">
                      review task open
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )}

        {rationale && (
          <section className="rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white p-4 dark:border-neutral-800 dark:bg-neutral-900">
            <h3 className="text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
              AI rationale {rationale.status === "refused" && "(refused by the citation gate)"}
            </h3>
            <p className="mt-2 whitespace-pre-wrap text-[13px] leading-relaxed text-neutral-700 dark:text-neutral-300">
              {rationale.rationale ?? rationale.note}
            </p>
          </section>
        )}
      </div>
    </div>
  );
}
