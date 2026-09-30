"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

/**
 * Company-brain query surface (F7). The question posts to
 * `/api/v1/company-brain/query`, which runs the judged workflow and returns
 * `{status, answer, verdict, note}` — a refused answer renders its note, not
 * a fabricated response.
 */

type Tenant = { tenantId: string; slug: string; role: string };

type BrainResult = {
  status: "completed" | "refused";
  answer?: string;
  note?: string | null;
  verdict?: {
    answers_probability: number;
    supported_probability: number;
  } | null;
};

export default function BrainPanel() {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [tenantId, setTenantId] = useState("");
  const [question, setQuestion] = useState("");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<BrainResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch("/api/v1/me/access")
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((d: { tenants: Tenant[] }) => {
        setTenants(d.tenants);
        if (d.tenants.length === 1) setTenantId(d.tenants[0].tenantId);
      })
      .catch(() => setError("could not load your tenants"));
  }, []);

  async function ask() {
    if (!tenantId || !question.trim()) return;
    setPending(true);
    setError(null);
    setResult(null);
    try {
      const r = await apiFetch("/api/v1/company-brain/query", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantId, question }),
      });
      if (r.status === 503) {
        setError("the agent service is not available on this deployment");
      } else if (!r.ok) {
        setError(`the query failed (HTTP ${r.status})`);
      } else {
        setResult((await r.json()) as BrainResult);
      }
    } catch {
      setError("the query could not reach the api");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-6 p-8">
      <header>
        <h1 className="text-xl font-semibold text-neutral-900 dark:text-neutral-50">
          Company brain
        </h1>
        <p className="mt-1 text-sm text-neutral-500">
          Ask across the platform&apos;s records. Answers are judged before they
          ship — a refused answer tells you the evidence wasn&apos;t there.
        </p>
      </header>

      {tenants.length > 1 && (
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-500">Tenant</span>
          <select
            className="rounded-md border border-neutral-300 bg-white px-3 py-2 dark:border-neutral-700 dark:bg-neutral-900"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
          >
            <option value="">choose…</option>
            {tenants.map((t) => (
              <option key={t.tenantId} value={t.tenantId}>
                {t.slug} — {t.role}
              </option>
            ))}
          </select>
        </label>
      )}

      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-500">Question</span>
        <textarea
          className="min-h-24 rounded-md border border-neutral-300 bg-white px-3 py-2 text-sm dark:border-neutral-700 dark:bg-neutral-900"
          placeholder="Who is standing at ic-review? What did diligence surface on PT Acme?"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          maxLength={2000}
        />
      </label>

      <button
        className="self-start rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40 dark:bg-neutral-100 dark:text-neutral-900"
        disabled={pending || !tenantId || !question.trim()}
        onClick={ask}
      >
        {pending ? "thinking…" : "Ask"}
      </button>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {result && (
        <section className="rounded-md border border-neutral-200 p-4 text-sm dark:border-neutral-800">
          {result.status === "completed" ? (
            <>
              <p className="whitespace-pre-wrap text-neutral-800 dark:text-neutral-200">
                {result.answer}
              </p>
              {result.verdict && (
                <p className="mt-3 text-xs text-neutral-400">
                  judged {Math.round(result.verdict.answers_probability * 100)}%
                  responsive · {Math.round(result.verdict.supported_probability * 100)}%
                  supported
                </p>
              )}
            </>
          ) : (
            <p className="text-neutral-500">
              {result.note ?? "the evidence in the platform could not support an answer"}
            </p>
          )}
        </section>
      )}
    </div>
  );
}
