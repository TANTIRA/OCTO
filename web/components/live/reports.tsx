"use client";

import { useCallback, useEffect, useState } from "react";
import { Plus, RefreshCw, ShieldCheck, Trash2 } from "lucide-react";
import { getJson, postJson } from "@/lib/api";
import { Button, IconButton } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/badge";
import { Field, Input, Select, Textarea } from "@/components/ui/controls";
import { EmptyState, InlineAlert, Skeleton, useToast } from "@/components/feedback";
import { WorkflowStepper } from "@/components/workflow/workflow";
import { CheckRow, JourneyGuide, dateTime, failure, shortId, useLiveRole } from "./common";

type Job = { id: string; type: string; positionSourceId?: string; status: "new" | "executing" | "done" | "error"; measures: string[]; error: string | null; createdAt: string };
type Release = { jobId: string; jobStatus: string; approvalTaskId: string | null; taskStatus: string | null; released: boolean; result: Record<string, unknown> | null; artifactSha256: string | null };
type Schedule = { id: string; name: string; type: string; positionSourceId: string; measures: string[]; cron: string; nextRunAt: string; active: boolean };
type Flow = { date: string; kind: "contribution" | "distribution"; amount: string };

const MEASURES = [
  { id: "tvpi", label: "TVPI", means: "Total value (distributions + NAV) per dollar paid in" },
  { id: "dpi", label: "DPI", means: "Cash returned per dollar paid in" },
  { id: "rvpi", label: "RVPI", means: "Remaining value (NAV) per dollar paid in" },
  { id: "irr", label: "IRR", means: "Annualised return from the dated cash flows and NAV" },
];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function stored(key: string): string[] {
  try {
    const v: unknown = JSON.parse(sessionStorage.getItem(key) ?? "[]");
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && UUID.test(x)) : [];
  } catch {
    return [];
  }
}
function store(key: string, ids: string[]) {
  try {
    sessionStorage.setItem(key, JSON.stringify(ids.slice(0, 20)));
  } catch {
    // Storage blocked: the jobs stay listed until the page is left.
  }
}

/**
 * Reports (live). A performance report is computed from figures a person
 * supplies and signs for — investor-signed cash flows and a NAV — so nothing
 * on this page is pre-filled. The computed result stays sealed until an
 * approver (never the requester) releases it; only then are the figures and
 * the artifact's fingerprint shown.
 */
export function ReportsView() {
  const { tenantId, canWrite, canDecide } = useLiveRole();
  const toast = useToast();
  const key = `octo.report-jobs.${tenantId}`;
  const [jobs, setJobs] = useState<Job[]>([]);
  const [releases, setReleases] = useState<Record<string, Release>>({});
  const [schedules, setSchedules] = useState<Schedule[] | null>(null);
  const [scheduleError, setScheduleError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const [fund, setFund] = useState("");
  const [currency, setCurrency] = useState("");
  const [nav, setNav] = useState("");
  const [valuationDate, setValuationDate] = useState("");
  const [flows, setFlows] = useState<Flow[]>([{ date: "", kind: "contribution", amount: "" }]);
  const [measures, setMeasures] = useState<string[]>(["tvpi", "dpi", "irr"]);
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const errs = {
    fund: !fund.trim() ? "Name the fund or portfolio this report is for." : null,
    currency: !/^[A-Z]{3}$/.test(currency) ? "3 letters, e.g. USD" : null,
    nav: nav.trim() === "" || !Number.isFinite(Number(nav)) || Number(nav) < 0 ? "Enter the NAV as a number, zero or more." : null,
    date: !valuationDate ? "Pick the valuation date the NAV is struck at." : null,
    flows: flows.some((f) => !f.date || f.amount.trim() === "" || !Number.isFinite(Number(f.amount)) || Number(f.amount) <= 0) ? "Every cash flow needs a date and a positive amount." : flows.some((f) => valuationDate && f.date > valuationDate) ? "Cash flows can’t be dated after the valuation date." : null,
    measures: measures.length === 0 ? "Choose at least one measure." : null,
  };
  const valid = Object.values(errs).every((e) => !e);
  const show = (k: keyof typeof errs) => (touched ? errs[k] ?? undefined : undefined);

  const refreshJob = useCallback(async (id: string) => {
    const [job, rel] = await Promise.all([getJson<Job>(`/api/v1/reports/${id}`), getJson<Release>(`/api/v1/reports/${id}/release`).catch(() => null)]);
    return { job, rel };
  }, []);

  const refreshAll = useCallback(
    async (ids: string[]) => {
      if (ids.length === 0) return;
      setRefreshing(true);
      try {
        const all = await Promise.all(ids.map((id) => refreshJob(id).catch(() => null)));
        const ok = all.filter((x): x is { job: Job; rel: Release | null } => x !== null);
        setJobs(ok.map((x) => x.job));
        setReleases(Object.fromEntries(ok.filter((x) => x.rel).map((x) => [x.job.id, x.rel!])));
      } finally {
        setRefreshing(false);
      }
    },
    [refreshJob],
  );

  useEffect(() => {
    if (!tenantId) return;
    refreshAll(stored(key));
    getJson<Schedule[]>(`/api/v1/report-schedules?tenantId=${tenantId}`)
      .then(setSchedules)
      .catch((e) => setScheduleError(failure(e)));
  }, [tenantId, key, refreshAll]);

  // Poll while anything is still computing or waiting for its approver.
  const pending = jobs.some((j) => j.status === "new" || j.status === "executing") || Object.values(releases).some((r) => r.approvalTaskId && !r.released && r.taskStatus === "open");
  useEffect(() => {
    if (!pending) return;
    const t = window.setTimeout(() => refreshAll(jobs.map((j) => j.id)), 3000);
    return () => window.clearTimeout(t);
  }, [pending, jobs, refreshAll]);

  const submit = async () => {
    setTouched(true);
    if (!valid || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const job = await postJson<Job>("/api/v1/reports", {
        tenantId,
        type: "performance",
        positionSourceType: "inline-series",
        positionSourceId: fund.trim(),
        measures,
        parameters: {
          currency,
          valuationDate,
          nav,
          // Investor-signed (§10.2): contributions are negative, distributions positive.
          flows: flows.map((f) => ({ date: f.date, amount: String(f.kind === "contribution" ? -Math.abs(Number(f.amount)) : Math.abs(Number(f.amount))) })),
        },
      });
      const ids = [job.id, ...stored(key)];
      store(key, ids);
      setJobs((j) => [job, ...j]);
      window.requestAnimationFrame(() => document.getElementById("report-jobs")?.scrollIntoView({ block: "start", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" }));
      toast({ tone: "ok", title: "Report queued", body: "It computes in a few seconds; then request its release." });
    } catch (e) {
      setError(failure(e, { 429: "Too many reports are running for this workspace. Wait for one to finish.", 400: "The inputs couldn’t be read — check the dates and amounts." }));
    } finally {
      setSubmitting(false);
    }
  };

  const requestRelease = async (id: string) => {
    try {
      const rel = await postJson<Release>(`/api/v1/reports/${id}/release`, {});
      setReleases((r) => ({ ...r, [id]: rel }));
      toast({ tone: "ok", title: "Release requested", body: "An approver other than you signs it off; the figures unseal once they do." });
    } catch (e) {
      toast({ tone: "danger", title: "Couldn’t request release", body: failure(e, { 409: "The report isn’t finished yet, or its release was already requested." }) });
    }
  };

  /** An approver's call on a release request; the requester is refused by the task itself (segregation of duties). */
  const decide = async (id: string, decision: "approve" | "reject", rationale?: string): Promise<boolean> => {
    try {
      const rel = await postJson<Release>(`/api/v1/reports/${id}/release/decision`, { decision, rationale: rationale?.trim() || undefined });
      setReleases((r) => ({ ...r, [id]: rel }));
      toast({ tone: "ok", title: decision === "approve" ? "Release approved" : "Release rejected", body: decision === "approve" ? "The figures and their fingerprint are now released." : "The report stays sealed; the reason is on the approval task." });
      return true;
    } catch (e) {
      toast({ tone: "danger", title: "The decision didn’t go through", body: failure(e, { 409: "You requested this release, so another approver must decide it — or it has already been decided.", 404: "Only an approver can decide a release.", 400: "A rejection needs a reason (up to 4,000 characters)." }) });
      return false;
    }
  };

  return (
    <div className="space-y-5">
      <JourneyGuide
        id="reports"
        steps={[
          { title: "Supply the evidence", body: "The fund’s NAV, its valuation date and the investor-signed cash flows. Nothing is pre-filled — you sign for these figures." },
          { title: "OCTO computes", body: "The chosen measures (TVPI, DPI, RVPI, IRR) are calculated in a job that takes a few seconds." },
          { title: "Request release", body: "The result is sealed until released. Requesting opens an approval task for someone other than you." },
          { title: "An approver decides", body: "An approver reads the sealed draft and approves or rejects it. Once approved, the figures and their SHA-256 fingerprint are released." },
        ]}
      />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
        <section aria-label="New performance report" className="rounded-lg border border-line bg-surface xl:col-span-7">
          <header className="border-b border-line px-4 py-3">
            <h2 className="text-card font-semibold text-ink">New performance report</h2>
            <p className="text-[12px] text-ink-3">Fields marked * are required.</p>
          </header>
          {!canWrite ? (
            <div className="p-4">
              <InlineAlert tone="restricted">Queuing a report needs a member or approver role in this workspace.</InlineAlert>
            </div>
          ) : (
            <form
              className="space-y-5 p-4"
              onSubmit={(e) => {
                e.preventDefault();
                submit();
              }}
            >
              <div className="grid gap-3 sm:grid-cols-2">
                <Field id="r-fund" label="Fund or portfolio" required error={show("fund")} hint="e.g. Flagship II — Q3 2026">
                  <Input id="r-fund" value={fund} onChange={(e) => setFund(e.target.value)} maxLength={200} />
                </Field>
                <div className="grid grid-cols-[6rem_1fr] gap-3">
                  <Field id="r-ccy" label="Currency" required error={show("currency")}>
                    <Input id="r-ccy" value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={3} placeholder="USD" />
                  </Field>
                  <Field id="r-nav" label="NAV at valuation date" required error={show("nav")}>
                    <Input id="r-nav" type="number" min="0" step="0.01" value={nav} onChange={(e) => setNav(e.target.value)} placeholder="0.00" />
                  </Field>
                </div>
                <Field id="r-date" label="Valuation date" required error={show("date")}>
                  <Input id="r-date" type="date" value={valuationDate} onChange={(e) => setValuationDate(e.target.value)} />
                </Field>
              </div>

              <fieldset>
                <legend className="text-[12px] font-medium text-ink-2">
                  Cash flows <span className="text-danger">*</span>
                </legend>
                <p className="mt-0.5 text-[12px] text-ink-3">Every capital call (contribution) and distribution up to the valuation date. Enter positive amounts — OCTO applies the investor sign for you.</p>
                <div className="mt-2 space-y-2">
                  {flows.map((f, i) => {
                    const set = (patch: Partial<Flow>) => setFlows(flows.map((x, j) => (j === i ? { ...x, ...patch } : x)));
                    return (
                      <div key={i} className="flex flex-wrap items-center gap-2">
                        <Input aria-label={`Flow ${i + 1} date`} type="date" value={f.date} onChange={(e) => set({ date: e.target.value })} className="w-40" />
                        <Select aria-label={`Flow ${i + 1} type`} value={f.kind} onChange={(e) => set({ kind: e.target.value as Flow["kind"] })} className="w-40">
                          <option value="contribution">Contribution</option>
                          <option value="distribution">Distribution</option>
                        </Select>
                        <Input aria-label={`Flow ${i + 1} amount`} type="number" min="0" step="0.01" value={f.amount} onChange={(e) => set({ amount: e.target.value })} placeholder="Amount" className="w-36" />
                        {flows.length > 1 && <IconButton size="sm" variant="ghost" label={`Remove flow ${i + 1}`} icon={<Trash2 />} onClick={() => setFlows(flows.filter((_, j) => j !== i))} />}
                      </div>
                    );
                  })}
                </div>
                <Button size="xs" variant="ghost" className="mt-1" onClick={() => setFlows([...flows, { date: "", kind: "distribution", amount: "" }])}>
                  <Plus /> Add cash flow
                </Button>
                {show("flows") && <p className="mt-1 text-[12px] text-danger">{errs.flows}</p>}
              </fieldset>

              <fieldset>
                <legend className="text-[12px] font-medium text-ink-2">Measures</legend>
                <div className="mt-2 grid gap-2 sm:grid-cols-2">
                  {MEASURES.map((m) => (
                    <CheckRow key={m.id} checked={measures.includes(m.id)} onChange={(v) => setMeasures((s) => (v ? [...s, m.id] : s.filter((x) => x !== m.id)))} label={m.label} hint={m.means} />
                  ))}
                </div>
                {show("measures") && <p className="mt-1 text-[12px] text-danger">{errs.measures}</p>}
              </fieldset>

              {error && <InlineAlert tone="danger" title="The report wasn’t queued">{error}</InlineAlert>}
              <div className="flex justify-end border-t border-line pt-4">
                <Button type="submit" variant="primary" loading={submitting}>
                  Queue report
                </Button>
              </div>
            </form>
          )}
        </section>

        <section aria-label="Scheduled reports" className="rounded-lg border border-line bg-surface xl:col-span-5">
          <header className="border-b border-line px-4 py-3">
            <h2 className="text-card font-semibold text-ink">Scheduled reports</h2>
            <p className="text-[12px] text-ink-3">Recurring reports run on their schedule and land in the same approval gate.</p>
          </header>
          {scheduleError ? (
            <div className="p-4">
              <InlineAlert tone="danger">{scheduleError}</InlineAlert>
            </div>
          ) : schedules === null ? (
            <div className="space-y-2 p-4">{[0, 1].map((i) => <Skeleton key={i} className="h-12 rounded-md" />)}</div>
          ) : schedules.length === 0 ? (
            <EmptyState title="No schedules" body="Schedules are defined by your OCTO administrator; each one queues a report job on its cadence." />
          ) : (
            <ul className="divide-y divide-line-subtle">
              {schedules.map((s) => (
                <li key={s.id} className="px-4 py-3">
                  <div className="flex items-center gap-2">
                    <span className="min-w-0 flex-1 truncate text-[13px] font-medium text-ink">{s.name}</span>
                    <StatusBadge tone={s.active ? "ok" : "neutral"}>{s.active ? "Active" : "Paused"}</StatusBadge>
                  </div>
                  <p className="mt-0.5 text-[12px] text-ink-3">
                    {s.measures.map((m) => m.toUpperCase()).join(", ")} · {s.active ? `next run ${dateTime(s.nextRunAt)}` : "not running"}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section id="report-jobs" aria-label="Your report jobs" className="scroll-mt-4 rounded-lg border border-line bg-surface">
        <header className="flex items-center justify-between gap-2 border-b border-line px-4 py-3">
          <div>
            <h2 className="text-card font-semibold text-ink">Your report jobs</h2>
            <p className="text-[12px] text-ink-3">Jobs queued from this browser session. Status refreshes on its own while something is pending.</p>
          </div>
          <IconButton size="sm" label="Refresh jobs" icon={<RefreshCw className={refreshing ? "animate-spin motion-reduce:animate-none" : undefined} />} onClick={() => refreshAll(jobs.map((j) => j.id))} />
        </header>
        {jobs.length === 0 ? (
          <EmptyState title="No report jobs yet" body="Queue a report above. It appears here with its progress through computation and release." />
        ) : (
          <ul className="divide-y divide-line-subtle">
            {jobs.map((j) => (
              <JobRow key={j.id} job={j} rel={releases[j.id]} onRelease={() => requestRelease(j.id)} onDecide={(d, why) => decide(j.id, d, why)} canWrite={canWrite} canDecide={canDecide} />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function JobRow({ job, rel, onRelease, onDecide, canWrite, canDecide }: { job: Job; rel: Release | undefined; onRelease: () => void; onDecide: (d: "approve" | "reject", why?: string) => Promise<boolean>; canWrite: boolean; canDecide: boolean }) {
  const [rejecting, setRejecting] = useState(false);
  const [why, setWhy] = useState("");
  const [busy, setBusy] = useState(false);
  const decide = async (d: "approve" | "reject") => {
    setBusy(true);
    const ok = await onDecide(d, d === "reject" ? why : undefined);
    setBusy(false);
    if (ok) {
      setRejecting(false);
      setWhy("");
    }
  };
  const requested = !!rel?.approvalTaskId;
  const released = !!rel?.released;
  const rejected = rel?.taskStatus === "rejected";
  const s = (cond: boolean, current: boolean) => (cond ? "done" : current ? "current" : "todo") as "done" | "current" | "todo";
  const steps = [
    { label: "Queued", state: s(job.status !== "new", job.status === "new") },
    { label: "Computed", state: s(job.status === "done", job.status === "executing") },
    { label: "Release requested", state: s(requested, job.status === "done" && !requested) },
    { label: "Approved & released", state: s(released, requested && !released) },
  ];
  return (
    <li className="space-y-3 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[13px] font-medium text-ink">{job.positionSourceId && !job.positionSourceId.startsWith("perf-") ? job.positionSourceId : "Performance report"}</span>
        <span className="text-[12px] text-ink-3">
          {job.measures.map((m) => m.toUpperCase()).join(", ")} · queued {dateTime(job.createdAt)} · job {shortId(job.id)}
        </span>
      </div>
      {job.status === "error" ? (
        <InlineAlert tone="danger" title="The computation failed">{job.error ?? "The job ended in error."} Check the inputs and queue it again.</InlineAlert>
      ) : (
        <WorkflowStepper steps={steps} />
      )}
      {job.status === "done" && !requested && canWrite && (
        <div className="flex flex-wrap items-center gap-3">
          <Button size="sm" variant="primary" onClick={onRelease}>
            <ShieldCheck /> Request release
          </Button>
          <span className="text-[12px] text-ink-3">The result is computed but sealed. An approver other than you must sign it off.</span>
        </div>
      )}
      {requested && !released && !rejected && !canDecide && <p className="text-[12px] text-ink-3">Waiting for an approver · task {shortId(rel?.approvalTaskId)}. The figures stay sealed until it is approved.</p>}
      {requested && !released && !rejected && canDecide && (
        <div className="space-y-3 rounded-md border border-accent-line bg-accent-soft/40 p-3">
          <div>
            <p className="text-[13px] font-semibold text-ink">Release decision · task {shortId(rel?.approvalTaskId)}</p>
            <p className="text-[12px] text-ink-3">You can read the sealed draft because you are an approver. You can’t decide a release you requested yourself.</p>
          </div>
          {rel?.result ? <Figures result={rel.result} sha={rel.artifactSha256} label="Sealed draft — visible to approvers only" /> : <p className="text-[12px] text-ink-3">The draft figures aren’t available to read yet.</p>}
          {rejecting ? (
            <div className="space-y-2">
              <Field id={`why-${job.id}`} label="Why are you rejecting it?" hint="Recorded on the approval task; the report stays sealed." required>
                <Textarea id={`why-${job.id}`} rows={2} value={why} onChange={(e) => setWhy(e.target.value)} maxLength={4000} autoFocus />
              </Field>
              <div className="flex gap-2">
                <Button size="sm" onClick={() => setRejecting(false)}>
                  Cancel
                </Button>
                <Button size="sm" variant="danger" disabled={!why.trim()} loading={busy} onClick={() => decide("reject")}>
                  Reject release
                </Button>
              </div>
            </div>
          ) : (
            <div className="flex gap-2">
              <Button size="sm" variant="primary" loading={busy} onClick={() => decide("approve")}>
                Approve release
              </Button>
              <Button size="sm" variant="danger" onClick={() => setRejecting(true)}>
                Reject…
              </Button>
            </div>
          )}
        </div>
      )}
      {rejected && <InlineAlert tone="warn">The release was rejected. Correct the inputs and queue a new report.</InlineAlert>}
      {released && rel?.result && <Figures result={rel.result} sha={rel.artifactSha256} label="Released" />}
    </li>
  );
}

/** The computed measures, formatted; IRR can be undefined for a series with no sign change. */
function Figures({ result, sha, label }: { result: Record<string, unknown>; sha: string | null; label: string }) {
  const shown = MEASURES.filter((m) => m.id in result);
  return (
    <div className="rounded-md border border-line bg-subtle p-3">
      <p className="mb-2 text-[11px] font-medium uppercase tracking-[0.04em] text-ink-3">{label}</p>
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {shown.map((m) => {
          const v = result[m.id];
          const n = v === null || v === undefined || v === "" ? NaN : Number(v);
          return (
            <div key={m.id}>
              <dt className="text-[12px] text-ink-3">{m.label}</dt>
              <dd className="text-metric font-semibold tabular-nums text-ink">{!Number.isFinite(n) ? "—" : m.id === "irr" ? `${(n * 100).toFixed(1)}%` : `${n.toFixed(2)}×`}</dd>
            </div>
          );
        })}
      </dl>
      {typeof result.methodology === "string" && <p className="mt-2 text-[12px] text-ink-3">{result.methodology}</p>}
      {sha && <p className="mt-1 break-all font-data text-[11px] text-ink-3">SHA-256 {sha}</p>}
    </div>
  );
}
