"use client";

import { useState } from "react";
import { AlertTriangle, CheckCircle2, ListTree, ShieldCheck, Sparkles, XCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import { useFormat } from "@/lib/use-format";
import { DEMO_NOW, hrefFor, type AiDraft } from "@/lib/demo";
import { Button } from "@/components/ui/button";
import { EntityChip } from "@/components/ui/badge";
import { Field, Tabs, Textarea } from "@/components/ui/controls";
import { Sheet } from "@/components/ui/overlay";
import { AiConfidence, VerificationBadge } from "./ai-badge";

export type DraftOutcome = "accepted" | "edited" | "evidence-requested" | "rejected";

const now = new Date(DEMO_NOW);

/** Numbered evidence list (AI-002): index, source label, document locator and source timestamp. */
export function EvidenceList({ citations, className }: { citations: AiDraft["citations"]; className?: string }) {
  const f = useFormat();
  return (
    <ol className={cn("divide-y divide-line-subtle rounded-md border border-line", className)} aria-label="Evidence">
      {citations.map((c, i) => (
        <li key={c.label} className="flex items-start gap-2.5 px-3 py-2 text-[12px]">
          <span className="mt-px flex size-5 shrink-0 items-center justify-center rounded-full bg-ai/10 text-[10px] font-semibold tabular-nums text-ai">{i + 1}</span>
          <span className="min-w-0 flex-1">
            <span className="block font-medium text-ink">{c.label}</span>
            <span className="block text-ink-3">{c.locator}</span>
          </span>
          <time className="shrink-0 text-ink-4" dateTime={c.asOf}>
            {f.date(c.asOf)}
          </time>
        </li>
      ))}
    </ol>
  );
}

type TraceTab = "inputs" | "evidence" | "checks" | "draft" | "actions";

const CHECK = {
  pass: { icon: <CheckCircle2 />, cls: "text-mark-ok", label: "Passed" },
  warn: { icon: <AlertTriangle />, cls: "text-mark-warn", label: "Warning" },
  fail: { icon: <XCircle />, cls: "text-mark-danger", label: "Failed" },
} as const;

/**
 * Draft trace (AI-003): what went in, what it cites, which deterministic checks
 * ran, the draft text and the action log. It deliberately shows no model
 * chain-of-thought — reviewers judge the output against evidence, not reasoning.
 */
export function DraftTrace({ draft }: { draft: AiDraft }) {
  const f = useFormat();
  const [tab, setTab] = useState<TraceTab>("inputs");
  const t = draft.trace;
  const warns = t.checks.filter((c) => c.result !== "pass").length;
  return (
    <div>
      <Tabs<TraceTab>
        label="Trace sections"
        variant="pill"
        value={tab}
        onChange={setTab}
        items={[
          { value: "inputs", label: "Inputs", count: t.inputs.length },
          { value: "evidence", label: "Evidence", count: draft.citations.length },
          { value: "checks", label: "Checks", count: t.checks.length },
          { value: "draft", label: "Draft" },
          { value: "actions", label: "Actions", count: t.actions.length },
        ]}
      />
      <div role="tabpanel" aria-label={tab} className="mt-4">
        {tab === "inputs" && (
          <ul className="divide-y divide-line-subtle rounded-md border border-line">
            {t.inputs.map((i) => (
              <li key={i.label} className="px-3 py-2 text-[12px]">
                <p className="font-medium text-ink">{i.label}</p>
                <p className="text-ink-3">{i.detail}</p>
              </li>
            ))}
          </ul>
        )}
        {tab === "evidence" && <EvidenceList citations={draft.citations} />}
        {tab === "checks" && (
          <>
            <p className="mb-2 text-[12px] text-ink-3">
              {t.checks.length} checks · {warns === 0 ? "all passed" : `${warns} need attention`}
            </p>
            <ul className="divide-y divide-line-subtle rounded-md border border-line">
              {t.checks.map((c) => (
                <li key={c.label} className="flex items-start gap-2.5 px-3 py-2 text-[12px]">
                  <span aria-hidden className={cn("mt-px [&>svg]:size-4", CHECK[c.result].cls)}>
                    {CHECK[c.result].icon}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block font-medium text-ink">
                      {c.label} <span className="font-normal text-ink-3">· {CHECK[c.result].label}</span>
                    </span>
                    <span className="block text-ink-3">{c.detail}</span>
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
        {tab === "draft" && <p className="rounded-md border border-line bg-subtle px-3 py-2.5 text-[13px] leading-relaxed text-ink-2">{draft.body}</p>}
        {tab === "actions" && (
          <ol className="space-y-2">
            {t.actions.map((a, i) => (
              <li key={i} className="flex items-baseline justify-between gap-3 text-[12px]">
                <span className="text-ink-2">
                  <span className="font-medium text-ink">{a.actor}</span> · {a.text}
                </span>
                <time className="shrink-0 text-ink-4" dateTime={a.at}>
                  {f.ago(a.at, now)}
                </time>
              </li>
            ))}
          </ol>
        )}
      </div>
      <p className="mt-4 flex items-start gap-1.5 text-[11px] text-ink-3">
        <ShieldCheck aria-hidden className="mt-px size-3.5 shrink-0" />
        The trace shows inputs, evidence, checks and actions. Model reasoning is not shown or stored.
      </p>
    </div>
  );
}

/**
 * Governed AI draft v2 (AI-001): company, title, summary, evidence, confidence,
 * status, generation stamp, decisions and a human-control line. Nothing is sent
 * or applied until a person chooses; reject and evidence requests need a note.
 * "View trace" opens the trace in a drawer, or inline when the card already
 * sits in one (`inlineTrace`).
 */
export function AiDraftCard({ draft, onResolve, inlineTrace = false, className }: { draft: AiDraft; onResolve: (outcome: DraftOutcome, note?: string) => void; inlineTrace?: boolean; className?: string }) {
  const f = useFormat();
  const [mode, setMode] = useState<"review" | "edit" | "reject" | "evidence">("review");
  const [text, setText] = useState(draft.body);
  const [note, setNote] = useState("");
  const [trace, setTrace] = useState(false);
  const needNote = (mode === "reject" || mode === "evidence") && note.trim().length < 5;

  return (
    <article className={cn("flex flex-col overflow-hidden rounded-lg border border-line bg-surface", className)} aria-label={`AI draft: ${draft.title}`}>
      <header className="space-y-2 px-5 pt-4">
        <div className="flex flex-wrap items-center gap-2">
          <EntityChip type={draft.entity.type} name={draft.entity.name} href={hrefFor(draft.entity)} />
          <span className="text-[12px] text-ink-3">{draft.kind}</span>
          <span className="ml-auto">
            <VerificationBadge state={draft.verification} />
          </span>
        </div>
        <h3 className="flex items-center gap-2 text-card font-semibold text-ink">
          <Sparkles aria-hidden className="size-4 shrink-0 text-ai" />
          {draft.title}
        </h3>
      </header>

      <div className="px-5 py-3">
        {mode === "edit" ? (
          <Field id={`${draft.id}-edit`} label="Edit before accepting" hint="Your edits are attributed to you in the audit trail.">
            <Textarea id={`${draft.id}-edit`} value={text} onChange={(e) => setText(e.target.value)} className="min-h-40" aria-describedby={`${draft.id}-edit-hint`} />
          </Field>
        ) : (
          <p className="text-[13px] leading-relaxed text-ink-2">{text}</p>
        )}
        {(mode === "reject" || mode === "evidence") && (
          <div className="mt-3">
            <Field id={`${draft.id}-note`} label={mode === "reject" ? "Why is this draft wrong?" : "What evidence is missing?"} required hint="Required. It is fed back to improve future drafts.">
              <Textarea id={`${draft.id}-note`} value={note} onChange={(e) => setNote(e.target.value)} aria-describedby={`${draft.id}-note-hint`} />
            </Field>
          </div>
        )}
      </div>

      <div className="px-5 pb-3">
        <div className="mb-2 flex items-center justify-between gap-2">
          <p className="text-[12px] font-semibold text-ink">Evidence</p>
          <AiConfidence level={draft.confidence} />
        </div>
        <EvidenceList citations={draft.citations} />
        <p className="mt-2 text-[11px] text-ink-4">
          Generated {f.ago(draft.createdAt, now)} by {draft.model} · {draft.id}
        </p>
      </div>

      {inlineTrace && trace && (
        <div className="border-t border-line-subtle px-5 py-4">
          <DraftTrace draft={draft} />
        </div>
      )}

      <footer className="mt-auto border-t border-line-subtle bg-subtle px-5 py-3">
        <div className="flex flex-wrap items-center gap-2">
          {mode === "review" && (
            <>
              <Button size="sm" variant="primary" onClick={() => onResolve("accepted")}>
                Accept
              </Button>
              <Button size="sm" onClick={() => setMode("edit")}>
                Edit
              </Button>
              <Button size="sm" onClick={() => setMode("evidence")}>
                Request evidence
              </Button>
              <Button size="sm" variant="danger" onClick={() => setMode("reject")}>
                Reject
              </Button>
            </>
          )}
          {mode === "edit" && (
            <>
              <Button size="sm" variant="primary" onClick={() => onResolve("edited", text)}>
                Accept edited draft
              </Button>
              <Button size="sm" variant="ghost" onClick={() => (setText(draft.body), setMode("review"))}>
                Cancel
              </Button>
            </>
          )}
          {(mode === "reject" || mode === "evidence") && (
            <>
              <Button size="sm" variant={mode === "reject" ? "danger" : "primary"} disabled={needNote} onClick={() => onResolve(mode === "reject" ? "rejected" : "evidence-requested", note)}>
                {mode === "reject" ? "Reject draft" : "Send evidence request"}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => (setNote(""), setMode("review"))}>
                Back
              </Button>
            </>
          )}
          <Button size="sm" variant="ghost" className="ml-auto" aria-expanded={inlineTrace ? trace : undefined} onClick={() => setTrace(!trace)}>
            <ListTree /> {inlineTrace && trace ? "Hide trace" : "View trace"}
          </Button>
        </div>
        <p className="mt-2 flex items-center gap-1.5 text-[11px] text-ink-3">
          <ShieldCheck aria-hidden className="size-3.5 shrink-0" />
          Nothing is sent or applied until a person decides.
        </p>
      </footer>

      {!inlineTrace && (
        <Sheet open={trace} onClose={() => setTrace(false)} eyebrow={`Trace · ${draft.id}`} title={draft.title}>
          <DraftTrace draft={draft} />
        </Sheet>
      )}
    </article>
  );
}
