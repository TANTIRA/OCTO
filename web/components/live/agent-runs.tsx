"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ArrowRight, RefreshCw } from "lucide-react";
import { cn } from "@/lib/utils";
import { getJson, postJson } from "@/lib/api";
import { Button, IconButton, LinkButton, ring } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/badge";
import { Field, SearchInput, Segmented, Select, Textarea } from "@/components/ui/controls";
import { Sheet } from "@/components/ui/overlay";
import { EmptyState, InlineAlert, Skeleton, useToast } from "@/components/feedback";
import { JourneyGuide, RUN_STATUS, WORKFLOW, Stat, ago, dateTime, failure, useLiveRole, workflowLabel } from "./common";
import { inputText, outputText, subjectLink, type AgentRun } from "./agent-runs-api";
import { STAGE_NAME, pct, type Prospect } from "./deals-api";

type Filter = "all" | "attention" | "running" | "completed";

/**
 * Agent runs (live): the audit spine behind "AI you can audit". Every AI
 * action anywhere in OCTO — a screening memo on a deal, an IC memo, a
 * compliance rationale, a company-brain answer — is one row: what it was
 * asked, which models ran, what the judge decided, and whether a person has
 * since agreed or overridden it. Each row links back to the deal or page it
 * was about.
 */
export function AgentRunsView() {
  const { tenantId } = useLiveRole();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const openId = params.get("run");
  const [runs, setRuns] = useState<AgentRun[]>([]);
  const [names, setNames] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [workflow, setWorkflow] = useState(params.get("workflow") ?? "");
  const [query, setQuery] = useState("");

  const setOpen = (id: string | null) => {
    const p = new URLSearchParams(params.toString());
    if (id) p.set("run", id);
    else p.delete("run");
    router.replace(`${pathname}${p.size ? `?${p}` : ""}`, { scroll: false });
  };

  const load = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true);
    setError(null);
    try {
      const list = await getJson<AgentRun[]>(`/api/v1/agent-runs?tenantId=${tenantId}&limit=200`);
      setRuns(list);
      // Name the deals the runs were about, so a row reads "Kirana Consumer", not a UUID.
      const ids = [...new Set(list.filter((r) => r.subjectType === "prospect").map((r) => r.subjectId.split(":")[0]))];
      const found = await Promise.all(ids.map((id) => getJson<Prospect>(`/api/v1/prospects/${id}`).then((p): [string, string] => [id, `${p.name} · ${STAGE_NAME[p.stage] ?? p.stage}`]).catch(() => null)));
      setNames(Object.fromEntries(found.filter((x): x is [string, string] => x !== null)));
    } catch (e) {
      setError(failure(e));
    } finally {
      setLoading(false);
    }
  }, [tenantId]);

  useEffect(() => {
    load();
  }, [load]);

  const counts = useMemo(
    () => ({
      attention: runs.filter((r) => r.status === "failed" || r.status === "refused").length,
      running: runs.filter((r) => r.status === "running").length,
      completed: runs.filter((r) => r.status === "completed").length,
      unreviewed: runs.filter((r) => r.status !== "running" && !r.humanOutcome).length,
    }),
    [runs],
  );
  const workflows = useMemo(() => [...new Set(runs.map((r) => r.workflow))].sort(), [runs]);
  const shown = runs.filter(
    (r) =>
      (filter === "all" || (filter === "attention" ? r.status === "failed" || r.status === "refused" : r.status === filter)) &&
      (!workflow || r.workflow === workflow) &&
      (!query.trim() || `${workflowLabel(r.workflow)} ${subjectName(r, names)} ${inputText(r.input) ?? ""}`.toLowerCase().includes(query.trim().toLowerCase())),
  );
  const open = runs.find((r) => r.id === openId) ?? null;

  return (
    <div className="space-y-5">
      <JourneyGuide
        id="agents"
        steps={[
          { title: "AI drafts, never decides", body: "Screening memos, IC memos, compliance rationales and brain answers are drafts. Moves and approvals stay with people." },
          { title: "A judge checks every draft", body: "A second model scores each draft against its evidence. A refused draft is stopped and nothing acts on it." },
          { title: "Everything is recorded", body: "Each run keeps what it was asked, which models ran, the judge’s scores and any error — the record auditors read." },
          { title: "People close the loop", body: "Mark whether you agree with a run or override it. Those outcomes calibrate how far the judge can be trusted." },
        ]}
      />

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Need attention" value={loading ? "…" : counts.attention} tone={counts.attention ? "warn" : undefined} />
        <Stat label="Running" value={loading ? "…" : counts.running} />
        <Stat label="Completed" value={loading ? "…" : counts.completed} tone="ok" />
        <Stat label="Awaiting a human review" value={loading ? "…" : counts.unreviewed} />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Segmented
          label="Status"
          value={filter}
          onChange={setFilter}
          items={[
            { value: "all", label: "All" },
            { value: "attention", label: `Need attention${counts.attention ? ` · ${counts.attention}` : ""}` },
            { value: "running", label: "Running" },
            { value: "completed", label: "Completed" },
          ]}
        />
        <Select aria-label="Workflow" value={workflow} onChange={(e) => setWorkflow(e.target.value)} className="w-48">
          <option value="">All workflows</option>
          {workflows.map((w) => (
            <option key={w} value={w}>
              {workflowLabel(w)}
            </option>
          ))}
        </Select>
        <SearchInput value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search deal, question…" aria-label="Search runs" className="w-full sm:w-60" />
        <IconButton className="ml-auto" label="Refresh runs" icon={<RefreshCw className={cn(loading && "animate-spin motion-reduce:animate-none")} />} onClick={load} disabled={loading} />
      </div>

      {error && (
        <InlineAlert tone="danger" title="Runs didn’t load" action={<Button size="sm" onClick={load}>Retry</Button>}>
          {error}
        </InlineAlert>
      )}

      <section aria-label="Agent runs" className="overflow-hidden rounded-lg border border-line bg-surface">
        {loading ? (
          <div className="space-y-2 p-4">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-14 rounded-md" />)}</div>
        ) : shown.length === 0 ? (
          <EmptyState title={runs.length ? "No run matches these filters" : "No AI runs yet"} body={runs.length ? "Clear a filter to see more runs." : "Runs appear here the first time someone asks for an AI screen, an IC memo, a compliance rationale or a brain answer."} action={!runs.length ? <LinkButton href="/app/deals">Go to the pipeline</LinkButton> : undefined} />
        ) : (
          <ul className="divide-y divide-line-subtle">
            {shown.map((r) => {
              const st = RUN_STATUS[r.status];
              return (
                <li key={r.id}>
                  <button type="button" onClick={() => setOpen(r.id)} className={cn("grid w-full cursor-pointer grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1 px-4 py-3 text-left hover:bg-hover sm:grid-cols-[minmax(0,1fr)_10rem_9rem_auto]", ring)}>
                    <span className="min-w-0">
                      <span className="block truncate text-[13px] font-medium text-ink">{workflowLabel(r.workflow)}</span>
                      <span className="block truncate text-[12px] text-ink-3">{inputText(r.input) ?? subjectName(r, names)}</span>
                    </span>
                    <span className="hidden sm:block">
                      <StatusBadge tone={st?.tone ?? "neutral"}>{st?.label ?? r.status}</StatusBadge>
                    </span>
                    <span className="hidden text-[12px] text-ink-3 sm:block">{r.humanOutcome ? <span className="text-ok">Reviewed</span> : r.status === "running" ? "—" : "Not reviewed"}</span>
                    <span className="flex items-center gap-2 text-[12px] tabular-nums text-ink-3">
                      <span className="sm:hidden">
                        <StatusBadge tone={st?.tone ?? "neutral"}>{st?.label ?? r.status}</StatusBadge>
                      </span>
                      {ago(r.createdAt)}
                      <ArrowRight aria-hidden className="size-3.5 text-ink-4" />
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {openId && <RunSheet run={open} loadingList={loading} names={names} onClose={() => setOpen(null)} onChanged={load} />}
    </div>
  );
}

function subjectName(r: AgentRun, names: Record<string, string>): string {
  if (r.subjectType === "prospect") return `Deal: ${names[r.subjectId.split(":")[0]] ?? "loading…"}`;
  if (r.subjectType === "tenant") return "Workspace question";
  if (r.workflow === "calibration") return "Judge calibration across the workspace";
  if (r.subjectType === "compliance" || r.subjectType === "portfolio") return `Compliance: ${r.subjectId}`;
  if (r.subjectType === "company") return `Company: ${r.subjectId}`;
  if (r.subjectType === "ddq") return `Questionnaire: ${r.subjectId}`;
  if (r.subjectType === "report-job") return `Report job ${r.subjectId.slice(0, 8)}`;
  return `${r.subjectType}: ${r.subjectId}`;
}

function RunSheet({ run, loadingList, names, onClose, onChanged }: { run: AgentRun | null; loadingList: boolean; names: Record<string, string>; onClose: () => void; onChanged: () => void }) {
  const { canWrite } = useLiveRole();
  const toast = useToast();
  const [decision, setDecision] = useState<"agree" | "override" | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!run) {
    return (
      <Sheet open onClose={onClose} eyebrow="Agent run" title={loadingList ? "Loading run…" : "Run not found"}>
        {loadingList ? <Skeleton className="h-40" /> : <EmptyState title="This run isn’t in your workspace" body="It may belong to another workspace, or the link is out of date." />}
      </Sheet>
    );
  }

  const st = RUN_STATUS[run.status];
  const link = subjectLink(run);
  const text = outputText(run.output);
  const scores = Object.entries(run.verdict ?? {}).filter(([, v]) => typeof v === "number") as [string, number][];
  const flags = Object.entries(run.verdict ?? {}).filter(([, v]) => typeof v === "boolean") as [string, boolean][];

  const record = async () => {
    if (!decision || (decision === "override" && !note.trim())) return;
    setBusy(true);
    setError(null);
    try {
      await postJson(`/api/v1/agent-runs/${run.id}/outcome`, { outcome: { verdict: decision, note: note.trim() || null } });
      toast({ tone: "ok", title: decision === "agree" ? "Recorded: you agree with this run" : "Recorded: you overrode this run" });
      setDecision(null);
      setNote("");
      onChanged();
    } catch (e) {
      setError(failure(e, { 400: "The note is too long to record." }));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet open onClose={onClose} eyebrow="Agent run" title={workflowLabel(run.workflow)} width="sm:max-w-[560px] lg:max-w-[620px]" footer={link ? <div className="flex justify-end"><LinkButton href={link.href} variant="primary" onClick={onClose}>{link.label} <ArrowRight /></LinkButton></div> : undefined}>
      <div className="space-y-6 text-[13px]">
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge tone={st?.tone ?? "neutral"}>{st?.label ?? run.status}</StatusBadge>
            <span className="text-ink-3">{dateTime(run.createdAt)}{run.finishedAt && ` · took ${Math.max(1, Math.round((new Date(run.finishedAt).getTime() - new Date(run.createdAt).getTime()) / 1000))}s`}</span>
          </div>
          <p className="text-ink-2">{st?.means}</p>
          {WORKFLOW[run.workflow] && <p className="text-ink-3">{WORKFLOW[run.workflow].does}</p>}
        </div>

        <Block title="About">
          <p className="text-ink">{subjectName(run, names)}</p>
          {inputText(run.input) && <p className="mt-1 text-ink-2">{inputText(run.input)}</p>}
          <p className="mt-1 text-[12px] text-ink-3">Started by {run.actor}</p>
        </Block>

        <Block title="What it produced">
          {run.error && <InlineAlert tone={run.status === "refused" ? "warn" : "danger"}>{run.error}</InlineAlert>}
          {text ? <p className="whitespace-pre-line leading-relaxed text-ink-2">{text}</p> : !run.error && <p className="text-ink-3">{run.status === "running" ? "Nothing yet — the run is still in progress." : "No text output was recorded."}</p>}
        </Block>

        {(scores.length > 0 || flags.length > 0) && (
          <Block title="Judge’s verdict">
            <ul className="space-y-2">
              {flags.map(([k, v]) => (
                <li key={k} className="flex items-center justify-between gap-3">
                  <span className="text-ink-2">{label(k)}</span>
                  <StatusBadge tone={v ? "ok" : "warn"}>{v ? "Yes" : "No"}</StatusBadge>
                </li>
              ))}
              {scores.map(([k, v]) => (
                <li key={k}>
                  <div className="flex items-center justify-between gap-3">
                    <span className="text-ink-2">{label(k)}</span>
                    <span className="font-medium tabular-nums text-ink">{pct(v)}</span>
                  </div>
                  <div aria-hidden className="mt-1 h-1.5 overflow-hidden rounded-full bg-sunken">
                    <div className="h-full rounded-full bg-accent" style={{ width: `${Math.round(v * 100)}%` }} />
                  </div>
                </li>
              ))}
            </ul>
          </Block>
        )}

        {run.models && (
          <Block title="Models">
            <dl className="grid grid-cols-2 gap-2">
              {Object.entries(run.models).map(([k, v]) => (
                <div key={k}>
                  <dt className="text-[12px] text-ink-3">{label(k)}</dt>
                  <dd className="font-data text-[12px] text-ink">{String(v)}</dd>
                </div>
              ))}
            </dl>
          </Block>
        )}

        <Block title="Human review">
          {run.humanOutcome ? (
            <InlineAlert tone={run.humanOutcome.verdict === "override" ? "warn" : "ok"} title={run.humanOutcome.verdict === "override" ? "Overridden" : "Agreed"}>
              By {String(run.humanOutcome.decided_by ?? "—")}
              {run.humanOutcome.note ? <> — “{String(run.humanOutcome.note)}”</> : null}
            </InlineAlert>
          ) : run.status === "running" ? (
            <p className="text-ink-3">You can review the run once it finishes.</p>
          ) : !canWrite ? (
            <p className="text-ink-3">Not reviewed yet. A member or approver can record whether they agree.</p>
          ) : (
            <div className="space-y-3">
              <p className="text-ink-2">Did the AI get this right? Your answer is stored with your name and used to calibrate the judge.</p>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant={decision === "agree" ? "primary" : "secondary"} onClick={() => setDecision("agree")} aria-pressed={decision === "agree"}>
                  I agree
                </Button>
                <Button size="sm" variant={decision === "override" ? "primary" : "secondary"} onClick={() => setDecision("override")} aria-pressed={decision === "override"}>
                  I disagree — override
                </Button>
              </div>
              {decision && (
                <Field id="outcome-note" label={decision === "override" ? "What should it have said?" : "Note (optional)"} required={decision === "override"}>
                  <Textarea id="outcome-note" rows={3} value={note} onChange={(e) => setNote(e.target.value)} maxLength={4000} />
                </Field>
              )}
              {error && <InlineAlert tone="danger">{error}</InlineAlert>}
              {decision && (
                <Button size="sm" variant="primary" loading={busy} disabled={decision === "override" && !note.trim()} onClick={record}>
                  Record review
                </Button>
              )}
            </div>
          )}
        </Block>
      </div>
    </Sheet>
  );
}

const label = (k: string) => k.replace(/_/g, " ").replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase());

function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section aria-label={title}>
      <h3 className="mb-2 text-[13px] font-semibold text-ink">{title}</h3>
      {children}
    </section>
  );
}
