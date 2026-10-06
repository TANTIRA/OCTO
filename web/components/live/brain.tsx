"use client";

import { useState } from "react";
import { ArrowRight, Check, X } from "lucide-react";
import { postJson } from "@/lib/api";
import { Button, LinkButton } from "@/components/ui/button";
import { Kbd, StatusBadge } from "@/components/ui/badge";
import { Textarea } from "@/components/ui/controls";
import { InlineAlert } from "@/components/feedback";
import { JourneyGuide, failure, useLiveRole } from "./common";
import { pct } from "./deals-api";

type BrainResult = { status: "completed" | "refused"; answer?: string; note?: string | null; verdict?: { ship?: boolean; answers_probability: number; supported_probability: number } | null };
type Turn = { id: number; question: string; at: string; result?: BrainResult; error?: string };

/** What the brain can read today (agents/octo_agents/workflows/company_brain.py tools). */
const CAN = ["The deal pipeline, stage by stage", "Any prospect’s state and its full history — who moved it, when and why", "Asset master records"];
const CANNOT = ["Positions, NAV or returns", "Documents and data-room files", "Anything outside this workspace"];
const EXAMPLES = ["Which prospects are waiting at IC review?", "Summarise the open pipeline by stage.", "Why was Bayu Wind’s screening refused?", "Who moved Kirana Consumer to IC review, and when?"];

/**
 * Company brain (live): plain-language questions over the workspace's own
 * records. A judge first decides whether the question is answerable from
 * them, then whether the drafted answer is supported; anything short of that
 * is refused, not guessed. Every question is a recorded agent run.
 */
export function BrainView() {
  const { tenantId } = useLiveRole();
  const [question, setQuestion] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [busy, setBusy] = useState(false);

  const ask = async (q = question) => {
    const text = q.trim();
    if (!text || busy || !tenantId) return;
    const id = Date.now();
    setTurns((t) => [{ id, question: text, at: new Date().toISOString() }, ...t]);
    setQuestion("");
    setBusy(true);
    try {
      const result = await postJson<BrainResult>("/api/v1/company-brain/query", { tenantId, question: text });
      setTurns((t) => t.map((x) => (x.id === id ? { ...x, result } : x)));
    } catch (e) {
      setTurns((t) => t.map((x) => (x.id === id ? { ...x, error: failure(e, { 400: "Questions are capped at 2,000 characters." }) } : x)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-5">
      <JourneyGuide
        id="brain"
        steps={[
          { title: "Ask in plain language", body: "About the pipeline, a prospect’s history or an asset. Anyone in the workspace can ask, including viewers." },
          { title: "Checked before answering", body: "A judge first decides whether the workspace records can answer the question at all." },
          { title: "Checked after answering", body: "The drafted answer is scored for support. Unsupported answers are refused — the brain never guesses." },
          { title: "On the record", body: "Every question and answer is a recorded run you can open in Agent runs, with its scores and models." },
        ]}
      />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
        <div className="space-y-4 xl:col-span-8">
          <form
            className="space-y-3 rounded-lg border border-line bg-surface p-4"
            onSubmit={(e) => {
              e.preventDefault();
              ask();
            }}
          >
            <label htmlFor="brain-q" className="text-card font-semibold text-ink">
              Ask the company brain
            </label>
            <Textarea
              id="brain-q"
              rows={3}
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
                  e.preventDefault();
                  ask();
                }
              }}
              placeholder="e.g. Which prospects are waiting at IC review?"
              maxLength={2000}
            />
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-wrap gap-1.5">
                {EXAMPLES.map((x) => (
                  <button key={x} type="button" onClick={() => ask(x)} disabled={busy} className="cursor-pointer rounded-full border border-line bg-subtle px-2.5 py-1 text-[12px] text-ink-2 hover:border-line-strong hover:text-ink disabled:cursor-default disabled:opacity-60">
                    {x}
                  </button>
                ))}
              </div>
              <Button type="submit" variant="primary" loading={busy} disabled={!question.trim()}>
                Ask <Kbd className="border-white/30 bg-white/10 text-white">Ctrl ↵</Kbd>
              </Button>
            </div>
          </form>

          {turns.length === 0 ? (
            <p className="px-1 text-[13px] text-ink-3">Your questions and answers from this visit appear here. Start with one of the examples above.</p>
          ) : (
            <ol className="space-y-3" aria-label="Questions and answers">
              {turns.map((t) => (
                <li key={t.id} className="space-y-2 rounded-lg border border-line bg-surface p-4">
                  <p className="text-[13px] font-medium text-ink">{t.question}</p>
                  {t.error ? (
                    <InlineAlert tone="danger" action={<Button size="sm" onClick={() => ask(t.question)}>Ask again</Button>}>
                      {t.error}
                    </InlineAlert>
                  ) : !t.result ? (
                    <p role="status" className="text-[13px] text-ink-3">
                      Checking the question, reading the records, then judging the answer…
                    </p>
                  ) : t.result.status === "completed" ? (
                    <>
                      <p className="whitespace-pre-line text-[13px] leading-relaxed text-ink-2">{t.result.answer}</p>
                      <Footer r={t.result} label="Answered" tone="ok" />
                    </>
                  ) : (
                    <>
                      <InlineAlert tone="warn" title="Not answered">
                        {t.result.note ?? "The records don’t support an answer to this question."} Try a question about the pipeline, a named prospect, or an asset.
                      </InlineAlert>
                      <Footer r={t.result} label="Refused" tone="warn" />
                    </>
                  )}
                </li>
              ))}
            </ol>
          )}
        </div>

        <aside className="space-y-4 xl:col-span-4">
          <section aria-label="What it can answer" className="rounded-lg border border-line bg-surface p-4">
            <h2 className="text-[13px] font-semibold text-ink">What it can answer today</h2>
            <ul className="mt-2 space-y-1.5 text-[13px] text-ink-2">
              {CAN.map((x) => (
                <li key={x} className="flex gap-2">
                  <Check aria-hidden className="mt-0.5 size-3.5 shrink-0 text-ok" />
                  {x}
                </li>
              ))}
            </ul>
            <h2 className="mt-4 text-[13px] font-semibold text-ink">Not yet</h2>
            <ul className="mt-2 space-y-1.5 text-[13px] text-ink-3">
              {CANNOT.map((x) => (
                <li key={x} className="flex gap-2">
                  <X aria-hidden className="mt-0.5 size-3.5 shrink-0" />
                  {x}
                </li>
              ))}
            </ul>
          </section>
          <section aria-label="Where to go next" className="rounded-lg border border-line bg-surface p-4">
            <h2 className="text-[13px] font-semibold text-ink">Acting on an answer</h2>
            <p className="mt-1 text-[13px] text-ink-3">The brain reads; it never changes anything. To act on what it tells you, open the deal in the pipeline.</p>
            <div className="mt-3 flex flex-wrap gap-2">
              <LinkButton size="sm" href="/app/deals">
                Open the pipeline <ArrowRight />
              </LinkButton>
              <LinkButton size="sm" variant="ghost" href="/app/agents?workflow=company-brain">
                Past answers in Agent runs
              </LinkButton>
            </div>
          </section>
        </aside>
      </div>
    </div>
  );
}

function Footer({ r, label, tone }: { r: BrainResult; label: string; tone: "ok" | "warn" }) {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-line-subtle pt-2 text-[12px] text-ink-3">
      <StatusBadge tone={tone}>{label}</StatusBadge>
      {r.verdict && (
        <>
          <span>
            Answerable from the records <span className="font-medium tabular-nums text-ink-2">{pct(r.verdict.answers_probability)}</span>
          </span>
          <span>
            Answer supported <span className="font-medium tabular-nums text-ink-2">{pct(r.verdict.supported_probability)}</span>
          </span>
        </>
      )}
      <LinkButton size="sm" variant="link" href="/app/agents?workflow=company-brain" className="ml-auto">
        View run
      </LinkButton>
    </div>
  );
}
