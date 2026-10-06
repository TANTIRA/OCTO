"use client";

import { useState } from "react";
import { AlertTriangle, Check, Info, Minus, OctagonAlert } from "lucide-react";
import { cn } from "@/lib/utils";
import type { Severity } from "@/lib/demo";
import { Button, LinkButton } from "@/components/ui/button";
import { EntityChip, StatusBadge, type Tone } from "@/components/ui/badge";
import { Field, Textarea } from "@/components/ui/controls";

/*
 * Workflow vocabulary (plan §17, FE-WORKFLOW): visible state, step progress,
 * one primary action per item, and decisions that always carry a reason.
 */

export const SEVERITY_TONE: Record<Severity, Tone> = { critical: "danger", high: "warn", medium: "info", low: "neutral" };

/** Severity: critical red · high amber · medium blue · low grey, each with its own icon (parity §19). */
const SEVERITY_ICON: Record<Severity, React.ReactNode> = { critical: <OctagonAlert />, high: <AlertTriangle />, medium: <Info />, low: <Minus /> };

export function SeverityBadge({ severity }: { severity: Severity }) {
  return (
    <StatusBadge tone={SEVERITY_TONE[severity]} icon={SEVERITY_ICON[severity]} className="capitalize">
      {severity}
    </StatusBadge>
  );
}

const STATE_TONE: Record<string, Tone> = {
  Open: "danger",
  "To do": "neutral",
  "In progress": "info",
  Investigating: "info",
  Acknowledged: "warn",
  Snoozed: "neutral",
  Escalated: "warn",
  Blocked: "danger",
  Resolved: "ok",
  Done: "ok",
  Approved: "ok",
  Rejected: "danger",
};

export function WorkflowStatus({ state }: { state: string }) {
  return <StatusBadge tone={STATE_TONE[state] ?? "neutral"}>{state}</StatusBadge>;
}

/** Horizontal approval/process stepper with a text summary for screen readers. */
export function WorkflowStepper({ steps }: { steps: { label: string; state: "done" | "current" | "todo" }[] }) {
  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-1" aria-label={`Progress: ${steps.filter((s) => s.state === "done").length} of ${steps.length} steps done`}>
      {steps.map((s, i) => (
        <li key={s.label} className="flex items-center gap-2 text-[12px]">
          <span
            className={cn(
              "flex size-5 items-center justify-center rounded-full border text-[10px] font-semibold",
              s.state === "done" && "border-mark-ok bg-mark-ok text-white",
              s.state === "current" && "border-accent bg-accent-soft text-accent-ink",
              s.state === "todo" && "border-line-strong text-ink-4",
            )}
          >
            {s.state === "done" ? <Check aria-hidden className="size-3" strokeWidth={3} /> : i + 1}
          </span>
          <span className={cn(s.state === "current" ? "font-medium text-ink" : "text-ink-3")}>
            {s.label}
            {s.state === "current" && <span className="sr-only"> (current)</span>}
          </span>
          {i < steps.length - 1 && <span aria-hidden className="h-px w-5 bg-line-strong" />}
        </li>
      ))}
    </ol>
  );
}

/** Feed / queue item: type, title, linked entity, time, severity, explanation, one primary action. */
export function WorkItem({
  kind,
  title,
  entity,
  severity,
  meta,
  body,
  action,
  onAction,
  href,
  secondary,
}: {
  kind: string;
  title: string;
  entity?: { type: string; name: string; href?: string };
  severity?: Severity;
  meta?: React.ReactNode;
  body?: string;
  action: string;
  onAction?: () => void;
  href?: string;
  secondary?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:gap-4">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[11px] font-semibold uppercase tracking-[0.05em] text-ink-4">{kind}</span>
          {severity && <SeverityBadge severity={severity} />}
        </div>
        <p className="mt-1 truncate text-[13px] font-semibold text-ink">{title}</p>
        {body && <p className="mt-0.5 line-clamp-2 text-[12px] text-ink-3">{body}</p>}
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-ink-3">
          {entity && <EntityChip type={entity.type} name={entity.name} href={entity.href} />}
          {meta}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2 self-start sm:self-auto">
        {secondary}
        {href ? (
          <LinkButton href={href} size="sm" variant="secondary">
            {action}
          </LinkButton>
        ) : (
          <Button size="sm" variant="secondary" onClick={onAction}>
            {action}
          </Button>
        )}
      </div>
    </div>
  );
}

export type Decision = { id: string; label: string; variant?: "primary" | "secondary" | "danger"; requireNote?: boolean };

/**
 * Decision bar for approvals and resolutions (plan §16, §17): the reason is the
 * audit record, so decisions that change state require a note.
 */
export function DecisionPanel({ decisions, onDecide, noteLabel = "Comment", hint = "Required. Stored with the audit record.", idPrefix }: { decisions: Decision[]; onDecide: (d: Decision, note: string) => void; noteLabel?: string; hint?: string; idPrefix: string }) {
  const [note, setNote] = useState("");
  const [tried, setTried] = useState<string | null>(null);
  const invalid = note.trim().length < 5;
  return (
    <div>
      <Field id={`${idPrefix}-note`} label={noteLabel} required error={tried && invalid ? "Add at least 5 characters so the decision is explained." : undefined} hint={hint}>
        <Textarea
          id={`${idPrefix}-note`}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          aria-invalid={!!tried && invalid}
          aria-describedby={tried && invalid ? `${idPrefix}-note-error` : `${idPrefix}-note-hint`}
        />
      </Field>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        {decisions.map((d) => (
          <Button
            key={d.id}
            size="sm"
            variant={d.variant ?? "secondary"}
            onClick={() => {
              if (d.requireNote !== false && invalid) return setTried(d.id);
              onDecide(d, note);
              setNote("");
              setTried(null);
            }}
          >
            {d.label}
          </Button>
        ))}
      </div>
    </div>
  );
}

/** Audit trail list used in drawers and object activity tabs. */
export function AuditTrail({ events }: { events: { id: string; actor: string; action: string; at: string; note?: string }[] }) {
  return (
    <ol className="space-y-2.5">
      {events.map((e) => (
        <li key={e.id} className="flex gap-2.5 text-[12px]">
          <span aria-hidden className="mt-1.5 size-1.5 shrink-0 rounded-full bg-ink-4" />
          <div className="min-w-0">
            <p className="text-ink-2">
              <span className="font-medium text-ink">{e.actor}</span> {e.action}
            </p>
            {e.note && <p className="mt-0.5 text-ink-3">“{e.note}”</p>}
            <p className="text-ink-4">{e.at}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}
