"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, Bot, ClipboardCheck, FileText, Gavel, ShieldCheck, Sparkles } from "lucide-react";
import { ApiError, getJson, postJson } from "@/lib/api";
import { supabase } from "@/lib/supabase";
import { Button, LinkButton } from "@/components/ui/button";
import { StatusBadge, type Tone } from "@/components/ui/badge";
import { Field, Select, Textarea } from "@/components/ui/controls";
import { Sheet } from "@/components/ui/overlay";
import { InlineAlert, Skeleton, useToast } from "@/components/feedback";
import { WorkflowStepper } from "@/components/workflow/workflow";
import { RUN_STATUS, ago, dateTime, failure, shortId, useLiveRole, workflowLabel } from "./common";
import { NEXT_STAGE, OPEN_STAGES, STAGE_NAME, WORKSTREAMS, pct, type AgentScreenResult, type DdReviewResult, type IcMemoResult, type IcReview, type Prospect, type ProspectEvent, type ScreenResult } from "./deals-api";
import type { AgentRun } from "./agent-runs-api";

/**
 * One deal, end to end: where it stands, the one or two things to do next at
 * this stage, its audit trail and every AI run on it. Each action is the
 * server's own transition or gate; the sheet never pre-judges what is legal,
 * it explains what the server answered.
 */
export function ProspectSheet({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { canWrite, canDecide } = useLiveRole();
  const toast = useToast();
  const [p, setP] = useState<Prospect | null>(null);
  const [events, setEvents] = useState<ProspectEvent[]>([]);
  const [ic, setIc] = useState<IcReview | null | undefined>(undefined);
  const [runs, setRuns] = useState<AgentRun[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [me, setMe] = useState<string | null>(null);

  useEffect(() => {
    supabase?.auth.getSession().then(({ data }) => setMe(data.session?.user.id ?? null));
  }, []);

  const load = useCallback(async () => {
    setError(null);
    try {
      const prospect = await getJson<Prospect>(`/api/v1/prospects/${id}`);
      const [ev, review, agentRuns] = await Promise.all([
        getJson<ProspectEvent[]>(`/api/v1/prospects/${id}/events`),
        getJson<IcReview>(`/api/v1/prospects/${id}/ic-review`).catch((e) => {
          if (e instanceof ApiError && e.status === 404) return null;
          throw e;
        }),
        getJson<AgentRun[]>(`/api/v1/agent-runs?tenantId=${prospect.tenantId}&subjectType=prospect&subjectId=${id}&limit=20`).catch(() => [] as AgentRun[]),
      ]);
      setP(prospect);
      setEvents(ev);
      setIc(review);
      setRuns(agentRuns);
    } catch (e) {
      setError(failure(e, { 404: "This prospect doesn’t exist in your workspaces, or you no longer have access to it." }));
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  /** Runs one write, then re-reads the deal and the board — the server is the only source of state. */
  const act = async <T,>(key: string, write: () => Promise<T>, done: (r: T) => string | null, context?: Partial<Record<number, string>>): Promise<T | null> => {
    if (busy) return null;
    setBusy(key);
    setActionError(null);
    try {
      const r = await write();
      const msg = done(r);
      if (msg) toast({ tone: "ok", title: msg });
      await load();
      onChanged();
      return r;
    } catch (e) {
      setActionError(failure(e, context));
      return null;
    } finally {
      setBusy(null);
    }
  };

  const terminal = p?.stage === "passed" || p?.stage === "invested";
  const stageIndex = OPEN_STAGES.findIndex((s) => s.id === p?.stage);

  return (
    <Sheet open onClose={onClose} eyebrow="Deal" title={p?.name ?? "Loading deal…"} width="sm:max-w-[600px] lg:max-w-[680px]">
      {error ? (
        <InlineAlert tone="danger" title="This deal didn’t load" action={<Button size="sm" onClick={load}>Retry</Button>}>
          {error}
        </InlineAlert>
      ) : !p ? (
        <div className="space-y-3">
          <Skeleton className="h-6 w-2/3" />
          <Skeleton className="h-24" />
          <Skeleton className="h-40" />
        </div>
      ) : (
        <div className="space-y-6">
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <StatusBadge tone={p.stage === "invested" ? "ok" : p.stage === "passed" ? "neutral" : "accent"}>{STAGE_NAME[p.stage] ?? p.stage}</StatusBadge>
              <span className="text-[12px] text-ink-3">
                {[p.sector, p.region].filter(Boolean).join(" · ") || "Sector and region not recorded"} · in this stage since {ago(p.lastEventAt)}
              </span>
            </div>
            {!terminal && <WorkflowStepper steps={[...OPEN_STAGES.map((s, i) => ({ label: s.name, state: (i < stageIndex ? "done" : i === stageIndex ? "current" : "todo") as "done" | "current" | "todo" })), { label: "Invested", state: "todo" as const }]} />}
          </div>

          {actionError && <InlineAlert tone="danger" title="That didn’t go through">{actionError}</InlineAlert>}

          {terminal ? (
            <Outcome p={p} events={events} />
          ) : !canWrite ? (
            <InlineAlert tone="restricted">You can follow this deal, but moving it needs a member or approver role in this workspace.</InlineAlert>
          ) : (
            <NextStep p={p} ic={ic} me={me} canDecide={canDecide} busy={busy} act={act} />
          )}

          <Section title="Details" icon={<FileText />}>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-[13px]">
              <Item label="Source" value={p.source} />
              <Item label="Registered" value={dateTime(p.registeredAt)} />
              <Item label="Sector" value={p.sector ?? "Not recorded"} />
              <Item label="Region" value={p.region ?? "Not recorded"} />
              <div className="col-span-2">
                <Item label="Thesis" value={p.description ?? "No thesis recorded. The AI memos work from it — add it in the CRM or re-register with one."} />
              </div>
            </dl>
          </Section>

          <Section title="AI activity on this deal" icon={<Bot />} hint="Every AI draft is recorded with its model and the judge’s verdict.">
            {runs.length === 0 ? (
              <p className="text-[13px] text-ink-3">No AI runs yet.</p>
            ) : (
              <ul className="divide-y divide-line-subtle rounded-md border border-line">
                {runs.map((r) => (
                  <li key={r.id}>
                    <Link href={`/app/agents?run=${r.id}`} className="flex items-center gap-3 px-3 py-2.5 text-[13px] hover:bg-hover">
                      <StatusBadge tone={RUN_STATUS[r.status]?.tone ?? "neutral"}>{RUN_STATUS[r.status]?.label ?? r.status}</StatusBadge>
                      <span className="min-w-0 flex-1 truncate text-ink">{workflowLabel(r.workflow)}</span>
                      <span className="shrink-0 text-[12px] text-ink-3">{ago(r.createdAt)}</span>
                      <ArrowRight aria-hidden className="size-3.5 text-ink-4" />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Section>

          <Section title="Audit trail" icon={<ShieldCheck />} hint="Append-only: who moved the deal, when, and why.">
            <ol className="space-y-3">
              {[...events].reverse().map((e) => (
                <li key={e.seq} className="flex gap-3 text-[13px]">
                  <span aria-hidden className="mt-1.5 size-2 shrink-0 rounded-full bg-accent" />
                  <div className="min-w-0">
                    <p className="text-ink">
                      <span className="font-medium">{e.actor}</span> {eventText(e)}
                    </p>
                    {e.rationale && <p className="mt-0.5 text-ink-2">“{e.rationale}”</p>}
                    <p className="mt-0.5 text-[12px] text-ink-3">
                      {dateTime(e.occurredAt)}
                      {e.taskId && <> · task {shortId(e.taskId)}</>}
                    </p>
                  </div>
                </li>
              ))}
            </ol>
          </Section>
        </div>
      )}
    </Sheet>
  );
}

type Act = <T>(key: string, write: () => Promise<T>, done: (r: T) => string | null, context?: Partial<Record<number, string>>) => Promise<T | null>;

/** The stage's own next actions — one primary, the rest secondary — plus Pass, which is always available. */
function NextStep({ p, ic, me, canDecide, busy, act }: { p: Prospect; ic: IcReview | null | undefined; me: string | null; canDecide: boolean; busy: string | null; act: Act }) {
  const [screen, setScreen] = useState<ScreenResult | null>(null);
  const [aiScreen, setAiScreen] = useState<AgentScreenResult | null>(null);
  const [memo, setMemo] = useState<IcMemoResult | null>(null);
  const [prompt, setPrompt] = useState<null | "pass" | "reject" | "invest">(null);
  const [reason, setReason] = useState("");
  const next = NEXT_STAGE[p.stage];

  const advance = () =>
    act("advance", () => postJson<Prospect>(`/api/v1/prospects/${p.id}/transition`, { to: next }), () => `${p.name} moved to ${STAGE_NAME[next]}.`, { 409: "The server refused the move — the deal changed since you opened it. It has been reloaded." });

  const confirm = async () => {
    const why = reason.trim();
    if (!why || !prompt) return;
    const ok =
      prompt === "pass"
        ? await act("pass", () => postJson(`/api/v1/prospects/${p.id}/transition`, { to: "passed", rationale: why }), () => `${p.name} passed.`)
        : prompt === "reject" && ic
          ? await act("reject", () => postJson(`/api/v1/prospects/${p.id}/tasks/${ic.taskId}`, { event: "rejected", rationale: why }), () => "IC review rejected.", { 409: "You requested this review, so another approver must decide it.", 404: "Only an approver can decide an IC review." })
          : prompt === "invest" && ic
            ? await act("invest", () => postJson(`/api/v1/prospects/${p.id}/transition`, { to: "invested", rationale: why, taskId: ic.taskId }), () => `${p.name} recorded as invested.`, { 409: "Only the latest, approved IC review can authorise the investment. Reload and check the review." })
            : null;
    if (ok !== null) {
      setPrompt(null);
      setReason("");
    }
  };

  const PROMPT = {
    pass: { title: "Pass on this deal", label: "Why are you passing?", hint: "Recorded on the audit trail with your name.", confirm: "Confirm pass" },
    reject: { title: "Reject the IC review", label: "Why is the IC rejecting it?", hint: "Recorded on the IC task. The deal stays at IC review and can be resubmitted.", confirm: "Reject review" },
    invest: { title: "Record the investment", label: "Investment rationale", hint: "Recorded with the approved IC review that authorises it.", confirm: "Record as invested" },
  } as const;

  return (
    <section aria-label="Next step" className="space-y-4 rounded-lg border border-accent-line bg-accent-soft/40 p-4">
      <div>
        <h3 className="text-[13px] font-semibold text-ink">Next step</h3>
        <p className="mt-0.5 text-[12px] text-ink-3">{OPEN_STAGES.find((s) => s.id === p.stage)?.does}</p>
      </div>

      {p.stage === "sourced" && (
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" loading={busy === "advance"} onClick={advance}>
            Move to screening <ArrowRight />
          </Button>
        </div>
      )}

      {p.stage === "screening" && (
        <div className="space-y-3">
          <div className="flex flex-wrap gap-2">
            <Button variant="primary" loading={busy === "screen"} onClick={async () => setScreen(await act("screen", () => postJson<ScreenResult>(`/api/v1/prospects/${p.id}/screen`, {}), (r) => (r.verdict === "reject" ? `${p.name} was screened out.` : null), { 409: "The deal is no longer at screening." }))}>
              <ClipboardCheck /> Run screening rules
            </Button>
            <Button loading={busy === "ai-screen"} onClick={async () => setAiScreen(await act("ai-screen", () => postJson<AgentScreenResult>(`/api/v1/prospects/${p.id}/agent-screen`, {}), () => null))}>
              <Sparkles /> AI first look
            </Button>
            <Button loading={busy === "advance"} onClick={advance}>
              Advance to due diligence <ArrowRight />
            </Button>
          </div>
          {screen && <ScreenOutcome r={screen} />}
          {aiScreen && <AiMemo title="AI screening memo" status={aiScreen.status} memo={aiScreen.memo} note={aiScreen.stage_note} scores={aiScreen.verdict ? [["Proceed", pct(aiScreen.verdict.proceed_probability)], ["Judge confidence", pct(aiScreen.verdict.confidence)]] : []} />}
        </div>
      )}

      {p.stage === "due-diligence" && <Diligence p={p} busy={busy} act={act} advance={advance} />}

      {p.stage === "ic-review" && (
        <div className="space-y-3">
          {ic === undefined ? (
            <Skeleton className="h-10" />
          ) : !ic || ic.taskStatus === "rejected" || ic.taskStatus === "cancelled" ? (
            <>
              {ic?.taskStatus === "rejected" && <InlineAlert tone="warn">The last IC review was rejected{ic.decidedBy ? ` by ${ic.decidedBy}` : ""}. Address the reason on the audit trail, then submit again.</InlineAlert>}
              <div className="flex flex-wrap gap-2">
                <Button variant="primary" loading={busy === "memo"} onClick={async () => setMemo(await act("memo", () => postJson<IcMemoResult>(`/api/v1/prospects/${p.id}/agent-ic-memo`, {}), (r) => (r.ic_review_requested ? "IC memo drafted and sent for approval." : null)))}>
                  <Sparkles /> Draft IC memo and request approval
                </Button>
                <Button loading={busy === "request"} onClick={() => act("request", () => postJson(`/api/v1/prospects/${p.id}/ic-review`, {}), () => "IC approval requested.", { 409: "The deal is no longer at IC review." })}>
                  <Gavel /> Request approval without a memo
                </Button>
              </div>
              <p className="text-[12px] text-ink-3">The AI memo is judged before it is sent; if the judge refuses it, nothing is requested and you can submit by hand.</p>
            </>
          ) : ic.taskStatus === "approved" ? (
            <>
              <InlineAlert tone="ok" title="IC approved">
                Approved by {ic.decidedBy ?? "an approver"}. Record the investment to close the deal.
              </InlineAlert>
              <Button variant="primary" onClick={() => setPrompt("invest")}>
                Record as invested <ArrowRight />
              </Button>
            </>
          ) : (
            <>
              <InlineAlert tone="info" title="Waiting for an IC decision">
                Requested by {ic.requestedBy}
                {me && ic.requestedBy === me ? " (you)" : ""} · task {shortId(ic.taskId)}. An approver who did not request it decides.
              </InlineAlert>
              {canDecide && ic.requestedBy !== me ? (
                <div className="flex flex-wrap gap-2">
                  <Button variant="primary" loading={busy === "approve"} onClick={() => act("approve", () => postJson(`/api/v1/prospects/${p.id}/tasks/${ic.taskId}`, { event: "approved" }), () => "IC review approved.", { 409: "You requested this review, so another approver must decide it (segregation of duties).", 404: "Only an approver can decide an IC review." })}>
                    Approve
                  </Button>
                  <Button variant="danger" onClick={() => setPrompt("reject")}>
                    Reject…
                  </Button>
                </div>
              ) : (
                <p className="text-[12px] text-ink-3">{canDecide ? "You requested this review, so you can’t decide it." : "Your role can’t decide IC reviews — an approver will."}</p>
              )}
            </>
          )}
          {memo && <AiMemo title="AI IC memo" status={memo.status} memo={memo.memo} note={memo.stage_note ?? (memo.status === "completed" && !memo.ic_review_requested ? "The memo passed but no new approval was opened — one may already be open." : null)} scores={memo.verdict ? [["Complete", pct(memo.verdict.complete_probability)], ["Evidence", pct(memo.verdict.evidence_score)]] : []} />}
        </div>
      )}

      {prompt ? (
        <form
          className="space-y-3 rounded-md border border-line bg-surface p-3"
          onSubmit={(e) => {
            e.preventDefault();
            confirm();
          }}
        >
          <p className="text-[13px] font-semibold text-ink">{PROMPT[prompt].title}</p>
          <Field id="reason" label={PROMPT[prompt].label} hint={PROMPT[prompt].hint} required>
            <Textarea id="reason" rows={3} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus maxLength={4000} />
          </Field>
          <div className="flex justify-end gap-2">
            <Button
              size="sm"
              onClick={() => {
                setPrompt(null);
                setReason("");
              }}
            >
              Cancel
            </Button>
            <Button size="sm" type="submit" variant={prompt === "invest" ? "primary" : "danger"} disabled={!reason.trim()} loading={busy !== null}>
              {PROMPT[prompt].confirm}
            </Button>
          </div>
        </form>
      ) : (
        <div className="border-t border-accent-line/60 pt-3">
          <Button size="sm" variant="ghost" onClick={() => setPrompt("pass")}>
            Pass on this deal…
          </Button>
        </div>
      )}
    </section>
  );
}

function Diligence({ p, busy, act, advance }: { p: Prospect; busy: string | null; act: Act; advance: () => void }) {
  const [workstream, setWorkstream] = useState("financial");
  const [summary, setSummary] = useState("");
  const [opened, setOpened] = useState<{ taskId: string; workstream: string; opened: boolean }[]>([]);
  const [review, setReview] = useState<DdReviewResult | null>(null);
  const runReview = async () =>
    setReview(await act("dd-review", () => postJson<DdReviewResult>(`/api/v1/prospects/${p.id}/agent-due-diligence`, {}), (r) => (r.tasks.some((t) => t.opened) ? "AI review opened evidence requests for the gaps it found." : null)));
  const flag = async () => {
    if (!summary.trim()) return;
    const r = await act("evidence", () => postJson<{ taskId: string; workstream: string; opened: boolean }>(`/api/v1/prospects/${p.id}/dd-evidence`, { workstream, summary: summary.trim() }), (x) => (x.opened ? `Evidence request opened for ${x.workstream}.` : `A ${x.workstream} request is already open.`));
    if (r) {
      setOpened((o) => [r, ...o]);
      setSummary("");
    }
  };
  return (
    <div className="space-y-3">
      <p className="text-[13px] text-ink-2">An evidence checklist was opened when the deal entered diligence. Ask the AI to review every workstream, or flag a gap yourself — each workstream gets one open request a person gathers against.</p>
      <Button loading={busy === "dd-review"} onClick={runReview}>
        <Sparkles /> AI diligence review
      </Button>
      {review && (
        <div className="space-y-2 rounded-md border border-line bg-surface p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[13px] font-semibold text-ink">AI diligence review</span>
            <StatusBadge tone={review.status === "completed" ? "ok" : "warn"}>{review.status === "completed" ? "Passed the judge" : "Refused by the judge"}</StatusBadge>
            {review.completeness !== null && (
              <span className="text-[12px] text-ink-3">
                Evidence complete <span className="font-medium tabular-nums text-ink-2">{pct(review.completeness)}</span>
              </span>
            )}
          </div>
          {review.dossier ? <p className="whitespace-pre-line text-[13px] leading-relaxed text-ink-2">{review.dossier}</p> : <p className="text-[13px] text-ink-3">No dossier was released.</p>}
          {review.bands.length > 0 && (
            <ul className="grid gap-1 text-[12px] sm:grid-cols-2">
              {review.bands.map((b) => (
                <li key={b.workstream} className="flex items-center justify-between gap-2 rounded-sm bg-subtle px-2 py-1">
                  <span className="text-ink-2">{WORKSTREAMS.find((w) => w.value === b.workstream)?.label ?? b.workstream}</span>
                  <span className="font-medium text-ink">
                    {b.band} <span className="font-normal text-ink-3">· {pct(b.confidence)}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
          {review.tasks.length > 0 && (
            <ul className="space-y-0.5 text-[12px] text-ink-2">
              {review.tasks.map((t) => (
                <li key={t.workstream}>
                  {WORKSTREAMS.find((w) => w.value === t.workstream)?.label ?? t.workstream}: {t.outcome_unknown ? "request may have been opened — check before re-running" : t.opened ? "evidence request opened" : "request already open"}
                  {t.task_id && <> · task {shortId(t.task_id)}</>}
                </li>
              ))}
            </ul>
          )}
          {review.task_errors.length > 0 && <InlineAlert tone="warn">Some requests couldn’t be opened: {review.task_errors.join("; ")}</InlineAlert>}
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-[10rem_1fr]">
        <Field id="ws" label="Workstream">
          <Select id="ws" value={workstream} onChange={(e) => setWorkstream(e.target.value)}>
            {WORKSTREAMS.map((w) => (
              <option key={w.value} value={w.value}>
                {w.label}
              </option>
            ))}
          </Select>
        </Field>
        <Field id="gap" label="What’s missing" hint="e.g. Audited FY25 accounts and the EBITDA bridge">
          <Textarea id="gap" rows={2} value={summary} onChange={(e) => setSummary(e.target.value)} maxLength={10_000} />
        </Field>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button loading={busy === "evidence"} disabled={!summary.trim()} onClick={flag}>
          Flag missing evidence
        </Button>
        <Button variant="primary" loading={busy === "advance"} onClick={advance}>
          Advance to IC review <ArrowRight />
        </Button>
      </div>
      {opened.length > 0 && (
        <ul className="space-y-1 text-[12px] text-ink-2">
          {opened.map((o) => (
            <li key={o.taskId}>
              {WORKSTREAMS.find((w) => w.value === o.workstream)?.label ?? o.workstream}: {o.opened ? "request opened" : "already open"} · task {shortId(o.taskId)}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const SCREEN_COPY: Record<ScreenResult["verdict"], { tone: "ok" | "warn" | "danger"; title: string; body: string }> = {
  clear: { tone: "ok", title: "Clear — inside the mandate", body: "Every active screening rule passed. Advance it to due diligence when you’re ready." },
  review: { tone: "warn", title: "Needs a person", body: "The rules couldn’t decide on the data recorded. A review task was opened; resolve it, then run the screen again or advance." },
  reject: { tone: "danger", title: "Screened out", body: "The deal breaks the mandate and was passed automatically, with these reasons on its audit trail." },
};

function ScreenOutcome({ r }: { r: ScreenResult }) {
  const c = SCREEN_COPY[r.verdict];
  return (
    <InlineAlert tone={c.tone} title={c.title}>
      <p>{c.body}</p>
      {r.reasons.length > 0 && (
        <ul className="mt-1 list-disc pl-4">
          {r.reasons.map((x) => (
            <li key={x}>{x}</li>
          ))}
        </ul>
      )}
      {r.reviewTaskId && <p className="mt-1">Review task {shortId(r.reviewTaskId)}</p>}
    </InlineAlert>
  );
}

function AiMemo({ title, status, memo, note, scores }: { title: string; status: "completed" | "refused"; memo: string; note: string | null; scores: [string, string][] }) {
  return (
    <div className="space-y-2 rounded-md border border-line bg-surface p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[13px] font-semibold text-ink">{title}</span>
        <StatusBadge tone={status === "completed" ? "ok" : "warn"}>{status === "completed" ? "Passed the judge" : "Refused by the judge"}</StatusBadge>
        {scores.map(([k, v]) => (
          <span key={k} className="text-[12px] text-ink-3">
            {k} <span className="font-medium tabular-nums text-ink-2">{v}</span>
          </span>
        ))}
      </div>
      {memo ? <p className="whitespace-pre-line text-[13px] leading-relaxed text-ink-2">{memo}</p> : <p className="text-[13px] text-ink-3">The judge refused the draft, so no memo was released. Strengthen the thesis and evidence, then try again.</p>}
      {note && <p className="text-[12px] text-ink-3">{note}</p>}
      <LinkButton size="sm" variant="link" href="/app/agents">
        See it in Agent runs
      </LinkButton>
    </div>
  );
}

function Outcome({ p, events }: { p: Prospect; events: ProspectEvent[] }) {
  const last = [...events].reverse().find((e) => e.stageTo === p.stage);
  const tone: Tone = p.stage === "invested" ? "ok" : "neutral";
  return (
    <InlineAlert tone={tone === "ok" ? "ok" : "info"} title={p.stage === "invested" ? "Invested" : "Passed"}>
      {p.stage === "invested" ? "Recorded as invested" : "Passed"} by {p.decidedBy ?? last?.actor ?? "—"} on {dateTime(last?.occurredAt)}.
      {last?.rationale && <> Reason: “{last.rationale}”</>}
      {p.stage === "invested" && " Positions appear in the portfolio once the investment’s transactions are booked in the IBOR."}
    </InlineAlert>
  );
}

function eventText(e: ProspectEvent): string {
  if (e.eventType === "registered") return "registered the prospect";
  if (e.stageTo === "passed") return `passed it at ${STAGE_NAME[e.stageFrom ?? ""] ?? e.stageFrom}`;
  if (e.stageTo === "invested") return "recorded it as invested";
  return `moved it from ${STAGE_NAME[e.stageFrom ?? ""] ?? e.stageFrom} to ${STAGE_NAME[e.stageTo ?? ""] ?? e.stageTo}`;
}

function Section({ title, icon, hint, children }: { title: string; icon: React.ReactNode; hint?: string; children: React.ReactNode }) {
  return (
    <section aria-label={title}>
      <h3 className="flex items-center gap-2 text-[13px] font-semibold text-ink [&>svg]:size-4 [&>svg]:text-ink-3">
        {icon}
        {title}
      </h3>
      {hint && <p className="mt-0.5 text-[12px] text-ink-3">{hint}</p>}
      <div className="mt-2">{children}</div>
    </section>
  );
}

function Item({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-[12px] text-ink-3">{label}</dt>
      <dd className="mt-0.5 break-words text-ink">{value}</dd>
    </div>
  );
}
