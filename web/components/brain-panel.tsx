"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";
import PanelHeader from "@/components/panel-header";
import { messageFor, postJson } from "@/lib/api";
import { apiFetch } from "@/lib/api";
import { useTenants } from "@/lib/use-tenants";

/**
 * Company-brain query surface (F7). The question posts to
 * `/api/v1/company-brain/query`, which runs the judged workflow and returns
 * `{status, answer, verdict, note}` — a refused answer renders its note, not
 * a fabricated response.
 */

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
  const { tenantId } = useTenants();
  const [question, setQuestion] = useState("");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<BrainResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function ask() {
    if (!tenantId || !question.trim() || pending) return;
    setPending(true);
    setError(null);
    setResult(null);
    try {
      setResult(await postJson<BrainResult>("/api/v1/company-brain/query", { tenantId, question }));
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="max-w-2xl">
      <PanelHeader
        description="Ask across the platform's records. Answers are judged before they ship — a refused answer tells you the evidence wasn't there."
        error={error}
      />

      <label className="flex flex-col gap-1.5">
        <span className="text-[13px] text-neutral-500">Question</span>
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-500">Question</span>
        <textarea
          className="min-h-24 rounded-[var(--rb-r-lg,10px)] border border-neutral-200/70 bg-white px-3 py-2 text-[13px] text-neutral-900 placeholder:text-neutral-400 dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-100"
          placeholder="Who is standing at ic-review? What did diligence surface on PT Acme?"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) ask();
          }}
          maxLength={2000}
        />
      </label>

      <button
        type="button"
        className="mt-3 inline-flex h-9 cursor-pointer items-center gap-2 rounded-[var(--rb-r-md,8px)] bg-[var(--rb-accent)] px-4 text-[13px] font-medium text-[var(--rb-accent-fg)] disabled:cursor-default disabled:opacity-50"
        disabled={pending || !tenantId || !question.trim()}
        onClick={ask}
      >
        {pending && <Loader2 aria-hidden className="h-4 w-4 animate-spin motion-reduce:animate-none" />}
        {pending ? "Thinking…" : "Ask"}
        {!pending && <kbd className="font-mono text-[11px] opacity-70">⌘↵</kbd>}
      </button>

      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}

      {result && (
        <section
          aria-live="polite"
          className="mt-4 rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-white p-4 text-[13px] dark:border-neutral-800 dark:bg-neutral-900"
        >
          {result.status === "completed" ? (
            <>
              <p className="whitespace-pre-wrap leading-relaxed text-neutral-800 dark:text-neutral-200">
                {result.answer}
              </p>
              {result.verdict && (
                <p className="mt-3 text-xs text-neutral-500">
                  Judged {Math.round(result.verdict.answers_probability * 100)}% responsive ·{" "}
                  {Math.round(result.verdict.supported_probability * 100)}% supported
                </p>
              )}
            </>
          ) : (
            <p className="text-neutral-600 dark:text-neutral-400">
              {result.note ?? "The evidence in the platform could not support an answer."}
            </p>
          )}
        </section>
      )}
    </div>
  );
}
