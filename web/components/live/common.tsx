"use client";

import { useEffect, useState } from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { ApiError, messageFor } from "@/lib/api";
import { useTenants } from "@/lib/use-tenants";
import { Checkbox } from "@/components/ui/controls";

/** The caller's role in the open workspace and what it allows — the UI only narrows; the API re-checks. */
export function useLiveRole() {
  const { tenants, tenantId } = useTenants();
  const role = tenants.find((t) => t.tenantId === tenantId)?.role ?? "viewer";
  return {
    tenantId,
    role,
    /** Register, move, run checks, request approvals. */
    canWrite: role === "member" || role === "approver" || role === "admin",
    /** Decide an approval gate. Admin is segregated from approval duties (TenantRole.ADMIN). */
    canDecide: role === "approver",
    /** Maintain rules (compliance, screening). */
    canGovern: role === "approver" || role === "admin",
  };
}

/**
 * A human message for a failed call, with the meaning of the status in the
 * context of the action ("why did this not work, and what do I do").
 */
export function failure(e: unknown, context: Partial<Record<number, string>> = {}): string {
  if (e instanceof ApiError && context[e.status]) return context[e.status]!;
  if (e instanceof ApiError && (e.status === 502 || e.status === 503)) return "The AI service is unavailable right now. Nothing was recorded — try again in a moment.";
  const m = messageFor(e);
  return m.charAt(0).toUpperCase() + m.slice(1) + ".";
}

/**
 * "How this works" — the page's job in a few numbered steps, so a first-time
 * user knows where they are in the journey before touching a control.
 * Collapsible; the choice is remembered per page in this browser only.
 */
export function JourneyGuide({ id, title = "How this works", steps, className }: { id: string; title?: string; steps: { title: string; body: string }[]; className?: string }) {
  const key = `octo.guide.${id}`;
  const [open, setOpen] = useState(true);
  useEffect(() => {
    try {
      if (localStorage.getItem(key) === "closed") setOpen(false);
    } catch {
      // Storage blocked: the guide simply starts open.
    }
  }, [key]);
  const toggle = () => {
    setOpen((v) => {
      try {
        localStorage.setItem(key, v ? "closed" : "open");
      } catch {
        // Storage blocked: the choice holds for this visit.
      }
      return !v;
    });
  };
  return (
    <section aria-label={title} className={cn("rounded-lg border border-line bg-subtle", className)}>
      <button type="button" onClick={toggle} aria-expanded={open} className="flex w-full cursor-pointer items-center justify-between gap-3 px-4 py-3 text-left focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-focus">
        <span className="text-[13px] font-semibold text-ink">{title}</span>
        <ChevronDown aria-hidden className={cn("size-4 text-ink-3 transition-transform", open && "rotate-180")} />
      </button>
      {open && (
        <ol className="grid gap-3 px-4 pb-4 sm:grid-cols-2 xl:grid-cols-4">
          {steps.map((s, i) => (
            <li key={s.title} className="flex gap-3">
              <span aria-hidden className="flex size-6 shrink-0 items-center justify-center rounded-full border border-accent-line bg-accent-soft text-[11px] font-semibold text-accent-ink">
                {i + 1}
              </span>
              <div className="min-w-0">
                <p className="text-[13px] font-medium text-ink">{s.title}</p>
                <p className="mt-0.5 text-[12px] leading-relaxed text-ink-3">{s.body}</p>
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** What each AI workflow does, in the words a deal or ops person uses. */
export const WORKFLOW: Record<string, { label: string; does: string }> = {
  "screening-dd": { label: "Screening memo", does: "Drafts a first-look screening memo for a prospect; a judge decides whether it proceeds." },
  "ic-memo": { label: "IC memo", does: "Drafts the investment-committee memo; a passing memo opens the IC approval." },
  "compliance-rationale": { label: "Compliance rationale", does: "Writes the narrative behind a compliance check, citing the rule outcomes." },
  "company-brain": { label: "Company brain answer", does: "Answers a question from the workspace's records, or refuses when they don't support one." },
  "document-extraction": { label: "Document extraction", does: "Reads figures out of an uploaded document." },
  "news-match": { label: "News match", does: "Matches news and filings to portfolio companies." },
  "due-diligence": { label: "Diligence review", does: "Flags missing evidence across diligence workstreams." },
  "ddq-response": { label: "DDQ response", does: "Drafts answers to a due-diligence questionnaire." },
  "equity-bridge": { label: "Equity bridge", does: "Narrates what moved equity value between two operating points." },
  "operating-review": { label: "Operating review", does: "Reviews a portfolio company's operating facts against its plan." },
  "lp-report": { label: "LP report draft", does: "Drafts LP report narrative from released figures." },
  calibration: { label: "Calibration", does: "Compares judge verdicts with human outcomes to measure agreement." },
};
export const workflowLabel = (w: string) => WORKFLOW[w]?.label ?? w.replace(/-/g, " ");

/** Agent run statuses, with what each means for the person reading it. */
export const RUN_STATUS: Record<string, { label: string; tone: "ok" | "warn" | "danger" | "info"; means: string }> = {
  completed: { label: "Completed", tone: "ok", means: "The model finished and the judge let the result through." },
  running: { label: "Running", tone: "info", means: "Still in progress — the result appears here when it closes." },
  refused: { label: "Refused by judge", tone: "warn", means: "The judge stopped the result because the evidence did not support it. Nothing was acted on." },
  failed: { label: "Failed", tone: "danger", means: "The run errored before producing a result. Nothing was acted on; it can be re-run." },
};

/**
 * A checkbox with a visible label and optional hint. The design-system
 * Checkbox is icon-only (built for table rows); here the text is clickable
 * too, and stays out of the accessibility tree because the box already
 * carries the label.
 */
export function CheckRow({ checked, onChange, label, hint }: { checked: boolean; onChange: (v: boolean) => void; label: string; hint?: string }) {
  return (
    <div className="flex items-start gap-2.5">
      <Checkbox checked={checked} onChange={onChange} label={label} className="mt-0.5" />
      <div className="min-w-0">
        <span aria-hidden onClick={() => onChange(!checked)} className="cursor-pointer select-none text-[13px] font-medium text-ink">
          {label}
        </span>
        {hint && <p className="text-[12px] text-ink-3">{hint}</p>}
      </div>
    </div>
  );
}

/** Small label/value pair for result summaries. */
export function Stat({ label, value, tone }: { label: string; value: React.ReactNode; tone?: "ok" | "warn" | "danger" }) {
  return (
    <div className="rounded-lg border border-line bg-surface px-4 py-3">
      <p className="text-[12px] text-ink-3">{label}</p>
      <p className={cn("mt-1 text-metric font-semibold tabular-nums", tone === "ok" ? "text-ok" : tone === "warn" ? "text-warn" : tone === "danger" ? "text-danger" : "text-ink")}>{value}</p>
    </div>
  );
}

/** Short, stable id for display ("task 3f2a9c1e"). */
export const shortId = (id: string | null | undefined) => (id ? id.slice(0, 8) : "—");

/** "3h ago", "2d ago" from an ISO instant. */
export function ago(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return "—";
  const s = Math.max(0, (now - new Date(iso).getTime()) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86_400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86_400)}d ago`;
}

export const dateTime = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString(undefined, { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "—";
