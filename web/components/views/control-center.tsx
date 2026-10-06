"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { AlertOctagon, ArrowRight, Banknote, Bell, Briefcase, CircleDollarSign, GitCompareArrows, History, Hourglass, Landmark, Percent, ShieldCheck, Sparkles, TrendingUp } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { useWorkspace } from "@/lib/workspace";
import { useAiDrafts, useAlerts, useApprovals, useExceptions, useFunds, usePortfolioMetrics, useRecon, useSignals, useTasks } from "@/lib/data/queries";
import { ACTIVITY, AS_OF, DEMO_NOW, SEVERITY_ORDER, fundDpi, fundTvpi, hrefFor, type Fund, type Metric, type Severity } from "@/lib/demo";
import { PageBody, PageHeader, PageSection } from "@/components/page/page-header";
import Dashboard4 from "@/components/blocks/dashboard-4";
import { LiveTenantGate } from "@/components/views/live";
import { Panel, PanelBody, PanelHead } from "@/components/page/panel";
import { MetricCard, MetricGrid } from "@/components/metric/metric-card";
import { LineageDrawer } from "@/components/metric/metric-lineage";
import { KpiMetricDrawer } from "@/components/metric/kpi-drawer";
import { useMetricValue } from "@/components/metric/metric-card";
import { NavChart } from "@/components/chart/nav-chart";
import { ActivityTimeline } from "@/components/chart/timeline";
import { DataTable, type Column } from "@/components/data/data-table";
import { EntityCell, NumericCell, StatusCell } from "@/components/data/cells";
import { Button, LinkButton } from "@/components/ui/button";
import { Tag, type Tone } from "@/components/ui/badge";
import { Tabs } from "@/components/ui/controls";
import { Sheet } from "@/components/ui/overlay";
import { EmptyState, FreshnessBadge, InlineAlert, Skeleton, useToast } from "@/components/feedback";
import { AiDraftCard } from "@/components/ai/ai-draft";
import { DecisionPanel, SeverityBadge, WorkItem, WorkflowStepper } from "@/components/workflow/workflow";
import { useBreadcrumb } from "@/components/shell/shell-context";

const now = new Date(DEMO_NOW);
const ICONS: Record<string, React.ReactNode> = { nav: <Landmark />, irr: <Percent />, tvpi: <TrendingUp />, dpi: <Banknote />, invested: <Briefcase />, dry: <CircleDollarSign /> };

type FeedKind = "task" | "approval" | "exception" | "recon" | "ai" | "alert" | "news";
type FeedItem = { id: string; kind: FeedKind; label: string; title: string; severity?: Severity; entity?: { type: string; name: string; href?: string }; at?: string; body?: string; action: string; href?: string };
type Filter = "all" | FeedKind;

const FILTERS: { value: Filter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "task", label: "My work" },
  { value: "approval", label: "Approvals" },
  { value: "exception", label: "Exceptions" },
  { value: "recon", label: "Recon" },
  { value: "ai", label: "AI drafts" },
  { value: "alert", label: "Alerts" },
  { value: "news", label: "News matches" },
];

const STATUS_TONE: Record<Fund["status"], Tone> = { Investing: "ok", Harvesting: "info", Watch: "warn", Exiting: "neutral", Fundraising: "accent" };

/**
 * Control Center (plan §17, §38 `/app`): one question — "what needs me, and
 * how is the portfolio doing?" Executive KPIs with lineage, a unified
 * priority feed with detail sheets, attention counts, fund performance,
 * signals and activity.
 */
export function ControlCenter() {
  const f = useFormat();
  const router = useRouter();
  const toast = useToast();
  const fmtMetric = useMetricValue();
  const { current } = useWorkspace();
  useBreadcrumb(null);

  const metrics = usePortfolioMetrics();
  const funds = useFunds();
  const tasks = useTasks();
  const approvals = useApprovals();
  const exceptions = useExceptions();
  const recon = useRecon();
  const drafts = useAiDrafts();
  const alerts = useAlerts();
  const signals = useSignals();

  const [lineage, setLineage] = useState<Metric | null>(null);
  const [kpi, setKpi] = useState<Metric | null>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [open, setOpen] = useState<FeedItem | null>(null);
  const [done, setDone] = useState<Set<string>>(new Set());

  const feed = useMemo<FeedItem[]>(() => {
    const items: FeedItem[] = [
      ...(tasks.data ?? []).filter((t) => t.assignee === "You" && t.status !== "Done").map((t) => ({ id: t.id, kind: "task" as const, label: `Task · ${t.type}`, title: t.title, severity: t.priority, entity: { type: t.entity.type, name: t.entity.name, href: hrefFor(t.entity) }, body: `${t.status} · due ${t.due}`, action: "Open" })),
      ...(approvals.data ?? []).map((a) => ({ id: a.id, kind: "approval" as const, label: `Approval · ${a.kind}`, title: a.title, severity: (a.due === "Today" ? "high" : "medium") as Severity, entity: { type: a.entity.type, name: a.entity.name, href: hrefFor(a.entity) }, at: a.requestedAt, body: a.summary, action: "Review" })),
      ...(exceptions.data ?? []).filter((e) => e.kind === "Valuation stale" || e.kind === "Covenant").map((e) => ({ id: e.id, kind: "exception" as const, label: `Exception · ${e.kind}`, title: e.title, severity: e.severity, entity: { type: e.entity.type, name: e.entity.name, href: hrefFor(e.entity) }, action: e.action, href: e.href })),
      ...(recon.data ?? []).filter((r) => r.state !== "Resolved").map((r) => ({ id: r.id, kind: "recon" as const, label: "Recon break", title: `${r.field} · ${r.variance}`, severity: r.severity, entity: { type: r.entity.type, name: r.entity.name, href: hrefFor(r.entity) }, body: r.cause, action: "Resolve", href: "/app/reconciliation" })),
      ...(drafts.data ?? []).map((d) => ({ id: d.id, kind: "ai" as const, label: `AI draft · ${d.kind}`, title: d.title, severity: "medium" as Severity, entity: { type: d.entity.type, name: d.entity.name, href: hrefFor(d.entity) }, at: d.createdAt, body: d.body, action: "Review draft" })),
      ...(alerts.data ?? []).filter((a) => a.state === "Open").map((a) => ({ id: a.id, kind: "alert" as const, label: "Alert", title: a.title, severity: a.severity, entity: { type: a.entity.type, name: a.entity.name, href: hrefFor(a.entity) }, at: a.triggeredAt, body: `Observed ${a.observed} vs ${a.threshold}`, action: "Review", href: `/app/alerts?id=${a.id}` })),
      ...(signals.data ?? []).filter((s) => s.entity).map((s) => ({ id: s.id, kind: "news" as const, label: s.kind, title: s.title, entity: s.entity ? { type: s.entity.type, name: s.entity.name, href: hrefFor(s.entity) } : undefined, at: s.at, body: s.body, action: "Open" })),
    ];
    return items.filter((i) => !done.has(i.id)).sort((a, b) => SEVERITY_ORDER[a.severity ?? "low"] - SEVERITY_ORDER[b.severity ?? "low"]);
  }, [tasks.data, approvals.data, exceptions.data, recon.data, drafts.data, alerts.data, signals.data, done]);

  const shown = filter === "all" ? feed : feed.filter((i) => i.kind === filter);
  const count = (k: FeedKind) => feed.filter((i) => i.kind === k).length;
  const loadingFeed = tasks.isLoading || approvals.isLoading || alerts.isLoading;

  const attention = [
    { label: "Covenant issues", value: (exceptions.data ?? []).filter((e) => e.kind === "Covenant").length, icon: <AlertOctagon />, href: "/app/alerts", tone: "text-danger" },
    { label: "Valuations stale", value: (exceptions.data ?? []).filter((e) => e.kind === "Valuation stale").length, icon: <Hourglass />, href: "/app/investments", tone: "text-warn" },
    { label: "Recon breaks", value: (recon.data ?? []).filter((r) => r.state !== "Resolved").length, icon: <GitCompareArrows />, href: "/app/reconciliation", tone: "text-warn" },
    { label: "Approvals waiting", value: (approvals.data ?? []).length, icon: <ShieldCheck />, href: "/app/workflows?tab=approvals", tone: "text-accent" },
  ];

  const fundCols: Column<Fund>[] = [
    { id: "name", header: "Fund", value: (x) => x.name, width: "26%", cell: (x) => <EntityCell name={x.name} sub={`${x.strategy} · ${x.vintage}`} href={`/app/funds/${x.slug}`} /> },
    { id: "committed", width: "12%", header: "Committed", value: (x) => x.committed, align: "right", cell: (x) => <NumericCell value={x.committed} muted /> },
    { id: "called", width: "12%", header: "Called", value: (x) => x.called, align: "right", cell: (x) => <NumericCell value={x.called} muted /> },
    { id: "nav", width: "12%", header: "NAV", value: (x) => x.nav, align: "right", cell: (x) => <NumericCell value={x.nav} /> },
    { id: "tvpi", width: "8%", header: "TVPI", value: (x) => fundTvpi(x), align: "right", cell: (x) => <NumericCell value={fundTvpi(x)} kind="multiple" /> },
    { id: "dpi", width: "8%", header: "DPI", value: (x) => fundDpi(x), align: "right", cell: (x) => <NumericCell value={fundDpi(x)} kind="multiple" /> },
    { id: "irr", width: "10%", header: "Net IRR", value: (x) => x.netIrr, align: "right", cell: (x) => <NumericCell value={x.netIrr} kind="pct" /> },
    { id: "status", width: "12%", header: "Status", value: (x) => x.status, cell: (x) => <StatusCell tone={STATUS_TONE[x.status]}>{x.status}</StatusCell> },
  ];


  const resolve = (item: FeedItem, title: string, body = "Recorded in this session only (demo).") => {
    setDone((d) => new Set(d).add(item.id));
    setOpen(null);
    toast({ tone: "ok", title, body });
  };

  return (
    <>
      <PageHeader
        variant="dashboard"
        eyebrow={`Control Center · ${current.name}`}
        title="What needs a decision today."
        description="Open work ranked by severity, and how the portfolio moved this quarter."
        meta={
          <>
            <FreshnessBadge state="demo" asOf={`as of ${f.date(AS_OF)}`} />
            <span className="text-[12px] font-medium text-ink-2">
              <span className="tabular-nums">{feed.length}</span> open items · <span className="tabular-nums">{count("approval")}</span> approvals waiting on you
            </span>
          </>
        }
        actions={
          <>
            <LinkButton href="/app/portfolio" size="md">
              Portfolio overview
            </LinkButton>
            <LinkButton href="/app/workflows" size="md" variant="primary">
              Open workflows <ArrowRight />
            </LinkButton>
          </>
        }
      />

      <PageBody className="space-y-6">
        <PageSection title="Live workspace" description="Pipeline, agent, compliance and report activity from the OCTO API." actions={<FreshnessBadge state="live" asOf="OCTO API" />}>
          <LiveTenantGate label="live workspace overview">
            <Dashboard4 />
          </LiveTenantGate>
        </PageSection>

        <InlineAlert tone="demo">Everything below — portfolio numbers, queues and feeds — is illustrative demo data until the API serves it.</InlineAlert>

        <MetricGrid cols={6}>
          {(metrics.data ?? Array.from({ length: 6 }, () => null)).map((m, i) =>
            m ? <MetricCard key={m.id} metric={m} icon={ICONS[m.id]} onOpen={setKpi} asOf={f.date(AS_OF)} /> : <MetricCard key={i} metric={{} as Metric} state="loading" />,
          )}
        </MetricGrid>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
          <Panel className="xl:col-span-8">
            <PanelHead
              title="Priority queue"
              icon={<Bell />}
              description="Sorted by severity. One primary action per item; details open without losing your place."
              toolbar={
                <LinkButton href="/app/workflows" size="sm" variant="ghost">
                  All work <ArrowRight />
                </LinkButton>
              }
            />
            <div className="px-5 pb-3">
              <Tabs<Filter> variant="pill" label="Filter priority queue" value={filter} onChange={setFilter} items={FILTERS.map((x) => ({ ...x, count: x.value === "all" ? feed.length : count(x.value) }))} />
            </div>
            <PanelBody flush fill={420} label="Priority queue items" className="border-t border-line">
              {loadingFeed ? (
                <div className="space-y-3 p-4" role="status" aria-label="Loading priority queue">
                  {[0, 1, 2, 3].map((i) => (
                    <div key={i} className="space-y-2">
                      <Skeleton className="h-3 w-24" />
                      <Skeleton className="h-4 w-2/3" />
                    </div>
                  ))}
                </div>
              ) : shown.length === 0 ? (
                <EmptyState icon={<ShieldCheck />} title="Nothing waiting here" body="New items appear when alerts fire, breaks are detected, drafts are generated, or approvals are requested." />
              ) : (
                <ul className="divide-y divide-line">
                  {shown.map((item) => (
                    <li key={item.id}>
                      <WorkItem
                        kind={item.label}
                        title={item.title}
                        entity={item.entity}
                        severity={item.severity}
                        body={item.body}
                        meta={item.at ? <span>{f.ago(item.at, now)}</span> : <span className="font-data text-[11px] text-ink-4">{item.id}</span>}
                        action={item.action}
                        href={item.href}
                        onAction={item.href ? undefined : () => setOpen(item)}
                      />
                    </li>
                  ))}
                </ul>
              )}
            </PanelBody>
          </Panel>

          <div className="grid grid-cols-1 content-start gap-4 xl:col-span-4">
            <Panel>
              <PanelHead title="Needs attention" icon={<AlertOctagon />} />
              <PanelBody>
                <ul className="grid grid-cols-2 gap-2">
                  {attention.map((a) => (
                    <li key={a.label}>
                      <button type="button" onClick={() => router.push(a.href)} className="flex w-full cursor-pointer flex-col items-start gap-1.5 rounded-lg border border-line p-3 text-left transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-focus">
                        <span className={`[&_svg]:size-4 ${a.tone}`}>{a.icon}</span>
                        <span className="text-kpi font-semibold tabular-nums text-ink">{a.value}</span>
                        <span className="text-[12px] text-ink-3">{a.label}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </PanelBody>
            </Panel>

            <Panel>
              <PanelHead title="Market & company signals" icon={<Sparkles />} toolbar={<Tag tone="info">Demo</Tag>} />
              <PanelBody flush>
                <ul className="divide-y divide-line">
                  {(signals.data ?? []).slice(0, 4).map((s) => (
                    <li key={s.id} className="px-4 py-3">
                      <div className="flex items-center justify-between gap-2">
                        <Tag tone={s.kind === "Portfolio update" ? "warn" : s.kind === "Regulatory event" ? "info" : "accent"}>{s.kind}</Tag>
                        <span className="text-[11px] text-ink-4">{f.ago(s.at, now)}</span>
                      </div>
                      <p className="mt-1.5 text-[13px] font-medium leading-snug text-ink">{s.title}</p>
                      <p className="mt-0.5 text-[12px] leading-relaxed text-ink-3">{s.body}</p>
                    </li>
                  ))}
                </ul>
              </PanelBody>
            </Panel>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
          <NavChart className="xl:col-span-8" height={360} state={metrics.isLoading ? "loading" : "ready"} onLineage={() => setLineage(metrics.data?.[0] ?? null)} />

          <Panel className="xl:col-span-4">
            <PanelHead title="Recent activity" icon={<History />} />
            <PanelBody fill={360} label="Recent activity events">
              <ActivityTimeline now={now} items={ACTIVITY.map((a) => ({ id: a.id, at: a.at, label: f.ago(a.at, now), title: `${a.actor} ${a.verb} ${a.object}`, state: a.state }))} />
            </PanelBody>
          </Panel>
        </div>

        <Panel>
          <PanelHead title="Fund performance" description="Net of fees, as of quarter end" toolbar={<LinkButton href="/app/funds" size="sm" variant="ghost">All funds <ArrowRight /></LinkButton>} />
          <DataTable
            id="cc-funds"
            chrome="minimal"
            label="Fund performance"
            data={funds.data ?? []}
            status={funds.isLoading ? "loading" : "ready"}
            columns={fundCols}
            rowId={(x) => x.id}
            onRowOpen={(x) => router.push(`/app/funds/${x.slug}`)}
            empty={{ title: "No funds", body: "Funds appear once a workspace has commitments recorded." }}
            totals={false}
          />
        </Panel>
      </PageBody>

      <KpiMetricDrawer metric={kpi} onClose={() => setKpi(null)} onLineage={(m) => (setKpi(null), setLineage(m))} primary={{ label: "View portfolio", href: "/app/portfolio" }} />
      <LineageDrawer open={!!lineage} onClose={() => setLineage(null)} title={lineage?.label ?? ""} value={lineage ? fmtMetric(lineage) : ""} provenance={lineage?.provenance ?? null} />

      <Sheet open={!!open} onClose={() => setOpen(null)} eyebrow={open?.label} title={open?.title ?? ""}>
        {open && <FeedDetail item={open} onResolve={resolve} />}
      </Sheet>
    </>
  );
}

function FeedDetail({ item, onResolve }: { item: FeedItem; onResolve: (item: FeedItem, title: string, body?: string) => void }) {
  const f = useFormat();
  const approvals = useApprovals();
  const drafts = useAiDrafts();
  const tasks = useTasks();
  const signals = useSignals();

  if (item.kind === "ai") {
    const d = drafts.data?.find((x) => x.id === item.id);
    return d ? <AiDraftCard draft={d} inlineTrace onResolve={(o) => onResolve(item, o === "rejected" ? "Draft rejected" : o === "evidence-requested" ? "Evidence requested" : "Draft accepted")} /> : null;
  }
  if (item.kind === "approval") {
    const a = approvals.data?.find((x) => x.id === item.id);
    if (!a) return null;
    return (
      <div className="space-y-5">
        <div className="flex flex-wrap items-center gap-2">
          {item.severity && <SeverityBadge severity={item.severity} />}
          <span className="text-[12px] text-ink-3">
            Requested by {a.requestedBy} · {f.ago(a.requestedAt, now)} · due {a.due}
          </span>
        </div>
        <WorkflowStepper steps={a.steps} />
        <p className="text-[13px] leading-relaxed text-ink-2">{a.summary}</p>
        <DecisionPanel
          idPrefix={`cc-${a.id}`}
          decisions={[
            { id: "reject", label: "Reject", variant: "danger" },
            { id: "changes", label: "Request changes" },
            { id: "approve", label: "Approve", variant: "primary" },
          ]}
          onDecide={(d) => onResolve(item, `${a.id} · ${d.label.toLowerCase()}`)}
        />
      </div>
    );
  }
  if (item.kind === "task") {
    const t = tasks.data?.find((x) => x.id === item.id);
    if (!t) return null;
    return (
      <div className="space-y-4 text-[13px]">
        <p className="text-ink-2">
          {t.type} task for <span className="font-medium text-ink">{t.entity.name}</span>, due {t.due}. Current status: {t.status}.
        </p>
        <div className="flex gap-2">
          <Button size="sm" variant="primary" onClick={() => onResolve(item, `${t.id} marked done`)}>
            Mark done
          </Button>
          <LinkButton size="sm" href={hrefFor(t.entity) ?? "/app"}>
            Open {t.entity.type.toLowerCase()}
          </LinkButton>
        </div>
      </div>
    );
  }
  const s = signals.data?.find((x) => x.id === item.id);
  return (
    <div className="space-y-3 text-[13px]">
      <p className="leading-relaxed text-ink-2">{item.body}</p>
      {s && <p className="text-[12px] text-ink-3">Source: {s.source}</p>}
      {item.entity?.href && (
        <LinkButton size="sm" href={item.entity.href}>
          Open {item.entity.name}
        </LinkButton>
      )}
    </div>
  );
}
