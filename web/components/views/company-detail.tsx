"use client";

import { useState } from "react";
import Link from "next/link";
import { notFound, useRouter, useSearchParams } from "next/navigation";
import { ArrowRight, FileText, Lock, Mail, Phone, ShieldAlert } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { useCompany } from "@/lib/data/queries";
import { ALERTS, AS_OF, DEMO_NOW, NAV_LINEAGE, SIGNALS, companyFinancials, daysAgo, fundById, investmentMoic, investmentsForCompany, type Metric } from "@/lib/demo";
import { PageBody } from "@/components/page/page-header";
import { Panel, PanelBody, PanelHead } from "@/components/page/panel";
import { ObjectHeader, ObjectLinks, ObjectMetadata } from "@/components/object/object";
import { MetricCard, MetricGrid, useMetricValue } from "@/components/metric/metric-card";
import { LineageDrawer } from "@/components/metric/metric-lineage";
import { KpiMetricDrawer } from "@/components/metric/kpi-drawer";
import { ChartShell } from "@/components/chart/chart-shell";
import { BarChart } from "@/components/chart/bar-chart";
import { TrendChart } from "@/components/chart/line-chart";
import { DonutChart } from "@/components/chart/donut-chart";
import { Legend } from "@/components/chart/core";
import { ActivityTimeline, type ActivityItem } from "@/components/chart/timeline";
import { DataTable, type Column } from "@/components/data/data-table";
import { NumericCell } from "@/components/data/cells";
import { Button, LinkButton } from "@/components/ui/button";
import { EntityChip, StatusBadge, Tag } from "@/components/ui/badge";
import { Tabs } from "@/components/ui/controls";
import { ConfirmDialog } from "@/components/ui/overlay";
import { EmptyState, FreshnessBadge, InlineAlert, MetricSkeleton, PermissionState, useToast } from "@/components/feedback";
import { SeverityBadge, WorkflowStatus } from "@/components/workflow/workflow";
import { useBreadcrumb } from "@/components/shell/shell-context";
import { COMPANY_TONE } from "./companies";

const TABS = ["overview", "financials", "performance", "ownership", "valuation", "risks", "documents", "news", "activity", "lineage"] as const;
type Tab = (typeof TABS)[number];
const now = new Date(DEMO_NOW);

/**
 * Company dossier (plan §14): object header plus ten tabs — Overview,
 * Financials, Performance, Ownership, Valuation, Risks, Documents, News,
 * Activity, Lineage. Arriving from a fund keeps that fund in the breadcrumb.
 */
export function CompanyDetail({ id }: { id: string }) {
  const f = useFormat();
  const router = useRouter();
  const params = useSearchParams();
  const toast = useToast();
  const fmt = useMetricValue();
  const q = useCompany(id);
  const c = q.data;
  const fromFund = params.get("fund") ? fundById(params.get("fund")!) : undefined;
  const tab = (TABS.includes(params.get("tab") as Tab) ? params.get("tab") : "overview") as Tab;
  const [lineage, setLineage] = useState<Metric | null>(null);
  const [kpi, setKpi] = useState<Metric | null>(null);
  const [markOpen, setMarkOpen] = useState(false);

  useBreadcrumb(
    c ? (fromFund ? [{ label: "Invest" }, { label: "Funds", href: "/app/funds" }, { label: fromFund.short, href: `/app/funds/${fromFund.slug}` }, { label: c.name }] : [{ label: "Invest" }, { label: "Companies", href: "/app/companies" }, { label: c.name }]) : null,
    c ? { type: "Company", name: c.name, href: `/app/companies/${c.id.toLowerCase()}` } : undefined,
  );

  if (q.isSuccess && !c) notFound();
  if (!c) {
    return (
      <PageBody>
        <MetricGrid cols={4}>
          {[0, 1, 2, 3].map((i) => (
            <MetricSkeleton key={i} />
          ))}
        </MetricGrid>
      </PageBody>
    );
  }

  const positions = investmentsForCompany(c.id);
  const fin = companyFinancials(c.id);
  const alerts = ALERTS.filter((a) => a.entity.id === c.id);
  const news = SIGNALS.filter((s) => s.entity?.id === c.id);
  const fv = positions.reduce((n, i) => n + i.fairValue, 0);
  const margin = c.revenue ? (c.ebitda / c.revenue) * 100 : 0;
  const lev = c.ebitda > 0 ? c.netDebt / c.ebitda : 0;
  const prov = (label: string, formula: string, inputs: { label: string; value: string }[]) => ({ formula, inputs, asOf: AS_OF, sourceSystem: "Portfolio monitoring · management accounts", sourceDocument: "Q3 management accounts", transformation: "LTM = sum of last four reported quarters", version: `${label} v1.0` });

  const kpis: Metric[] = c.revenue
    ? [
        { id: "rev", label: "LTM revenue", value: c.revenue, format: "money", delta: c.revenueGrowth, deltaUnit: "%", upIsGood: true, comparison: "YoY", spark: fin.map((x) => x.revenue), provenance: prov("Revenue", "Σ last 4 quarters revenue", fin.slice(-4).map((x) => ({ label: x.q, value: `$${x.revenue}M` }))) },
        { id: "ebitda", label: "LTM EBITDA", value: c.ebitda, format: "money", delta: c.id === "CMP-0187" ? -6.1 : 4.2, deltaUnit: "%", upIsGood: true, comparison: "QoQ", spark: fin.map((x) => x.ebitda), provenance: prov("EBITDA", "Σ last 4 quarters EBITDA", fin.slice(-4).map((x) => ({ label: x.q, value: `$${x.ebitda}M` }))) },
        { id: "margin", label: "EBITDA margin", value: margin, format: "pct", comparison: "LTM", provenance: prov("Margin", "LTM EBITDA ÷ LTM revenue", [{ label: "EBITDA", value: f.money(c.ebitda) }, { label: "Revenue", value: f.money(c.revenue) }]) },
        { id: "lev", label: "Net debt / EBITDA", value: lev, format: "multiple", comparison: c.status === "Covenant breach" ? "Covenant 1.20× DSCR breached" : "Policy ceiling 5.5×", upIsGood: false, provenance: prov("Leverage", "Net debt ÷ LTM EBITDA", [{ label: "Net debt", value: f.money(c.netDebt) }, { label: "EBITDA", value: f.money(c.ebitda) }]) },
      ]
    : [];

  const setTab = (t: Tab) => {
    const p = new URLSearchParams(params.toString());
    if (t === "overview") p.delete("tab");
    else p.set("tab", t);
    router.replace(`/app/companies/${c.id.toLowerCase()}${p.size ? `?${p}` : ""}`, { scroll: false });
  };

  const docs = [
    { id: "DOC-1", name: "Q3 management accounts", type: "Financials", date: daysAgo(12), source: "Document vault", restricted: false },
    { id: "DOC-2", name: "Q3 compliance certificate", type: "Covenant", date: daysAgo(3), source: "Lender portal", restricted: false },
    { id: "DOC-3", name: "Board pack — September", type: "Board", date: daysAgo(20), source: "Board portal", restricted: false },
    { id: "DOC-4", name: `Valuation memo ${positions[0]?.id ?? ""}`, type: "Valuation", date: positions[0]?.valuationDate ?? daysAgo(30), source: "Valuation team", restricted: false },
    { id: "DOC-5", name: "Shareholder agreement", type: "Legal", date: daysAgo(400), source: "Legal", restricted: true },
  ];
  type Doc = (typeof docs)[number];
  const docCols: Column<Doc>[] = [
    { id: "name", header: "Document", width: 280, value: (d) => (d.restricted ? "Restricted document" : d.name), cell: (d) => (d.restricted ? <PermissionState compact /> : <span className="inline-flex items-center gap-2 font-medium text-ink"><FileText aria-hidden className="size-3.5 text-ink-3" />{d.name}</span>) },
    { id: "type", header: "Type", value: (d) => (d.restricted ? "—" : d.type), facet: true },
    { id: "date", header: "Date", value: (d) => d.date, align: "right", cell: (d) => f.date(d.date) },
    { id: "source", header: "Source", value: (d) => (d.restricted ? "—" : d.source) },
  ];

  return (
    <>
      <ObjectHeader
        type="Portfolio company"
        name={c.name}
        status={<StatusBadge tone={COMPANY_TONE[c.status]}>{c.status}</StatusBadge>}
        classification={[c.sector, c.subsector, c.geography]}
        facts={[
          { label: "Deal lead", value: c.owner },
          { label: "Primary fund", value: positions[0] ? <Link className="hover:text-accent" href={`/app/funds/${positions[0].fundId.toLowerCase()}`}>{fundById(positions[0].fundId)!.short}</Link> : "—" },
          { label: "Fair value", value: f.money(fv) },
          { label: "Risk flags", value: c.risks.length ? c.risks.join(", ") : "None" },
        ]}
        freshness={<FreshnessBadge state={positions.some((p) => p.valuationAgeDays > 30) ? "stale" : "demo"} asOf={positions[0] ? `marked ${f.date(positions[0].valuationDate)}` : undefined} />}
        permission="Deal team, portfolio ops"
        actions={
          <>
            <Button onClick={() => setTab("documents")}>
              <FileText /> Latest board pack
            </Button>
            <Button variant={positions.some((p) => p.valuationAgeDays > 30) ? "primary" : "secondary"} onClick={() => setMarkOpen(true)}>
              Request new valuation mark
            </Button>
          </>
        }
        tabs={<Tabs<Tab> label="Company sections" value={tab} onChange={setTab} className="border-b-0" items={TABS.map((t) => ({ value: t, label: t[0].toUpperCase() + t.slice(1), count: t === "risks" ? c.risks.length + alerts.length || undefined : t === "news" ? news.length || undefined : undefined }))} />}
      />

      <PageBody className="space-y-6">
        {tab === "overview" && (
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
            <div className="space-y-4 xl:col-span-4">
              <Panel>
                <PanelHead title="Profile" />
                <PanelBody className="space-y-4">
                  <p className="text-[13px] leading-relaxed text-ink-2">{c.description}</p>
                  <ObjectMetadata items={[{ label: "Headquarters", value: c.hq }, { label: "Founded", value: c.founded }, { label: "Employees", value: c.employees ? f.num(c.employees) : "—" }, { label: "Stage", value: c.stage }]} />
                </PanelBody>
              </Panel>
              <Panel>
                <PanelHead title="Ownership" />
                <PanelBody className="pt-0">
                  <ObjectLinks links={positions.map((p) => ({ type: `${fundById(p.fundId)!.short} · ${p.instrument}`, name: fundById(p.fundId)!.name, href: `/app/funds/${p.fundId.toLowerCase()}`, meta: p.ownership ? f.pct(p.ownership, 0) : "Debt" }))} />
                </PanelBody>
              </Panel>
              <Panel>
                <PanelHead title="Contacts" />
                <PanelBody className="space-y-3 pt-1 text-[13px]">
                  {[
                    { name: "Chief Executive Officer", who: "Management", mail: true },
                    { name: "Chief Financial Officer", who: c.risks.includes("Key person") ? "Vacant — search in progress" : "Management", mail: !c.risks.includes("Key person") },
                    { name: c.owner, who: "Deal lead · OCTO", mail: true },
                  ].map((p) => (
                    <div key={p.name} className="flex items-center justify-between gap-2">
                      <div>
                        <p className="font-medium text-ink">{p.name}</p>
                        <p className="text-[12px] text-ink-3">{p.who}</p>
                      </div>
                      {p.mail && (
                        <span className="flex gap-1 text-ink-4">
                          <Mail aria-label="Email on file" className="size-3.5" />
                          <Phone aria-label="Phone on file" className="size-3.5" />
                        </span>
                      )}
                    </div>
                  ))}
                </PanelBody>
              </Panel>
              <Panel>
                <PanelHead title="Key dates" />
                <PanelBody className="pt-1">
                  <ObjectMetadata columns={1} items={[{ label: "First investment", value: positions[0] ? f.date(positions[0].entryDate) : "—" }, { label: "Last board meeting", value: f.date(daysAgo(20)) }, { label: "Next covenant test", value: f.date(daysAgo(-31)) }, { label: "Next valuation", value: f.date(daysAgo(-45)) }]} />
                </PanelBody>
              </Panel>
            </div>
            <div className="space-y-4 xl:col-span-8">
              {c.status === "Covenant breach" && <InlineAlert tone="danger" title="Covenant breach" action={<LinkButton size="xs" href="/app/alerts?id=ALR-1841">Open alert</LinkButton>}>DSCR 1.14× against a 1.20× covenant on the latest compliance certificate.</InlineAlert>}
              {kpis.length > 0 ? (
                <MetricGrid cols={4}>
                  {kpis.map((m) => (
                    <MetricCard key={m.id} metric={m} variant="compact" onOpen={setKpi} />
                  ))}
                </MetricGrid>
              ) : (
                <EmptyState title="No operating data" body="This company has been fully realised; historical financials are in Documents." />
              )}
              {fin.length > 0 && (
                <ChartShell title="Financial trend" subtitle="Quarterly revenue and EBITDA, $M" legend={<Legend items={[{ label: "Revenue", color: "var(--color-chart-1)" }, { label: "EBITDA", color: "var(--color-chart-2)" }]} />} source="Management accounts" height={220} exportData={{ filename: `${c.id}-financials`, head: ["Quarter", "Revenue", "EBITDA"], rows: fin.map((x) => [x.q, x.revenue, x.ebitda]) }}>
                  <BarChart x={fin.map((x) => x.q)} series={[{ id: "rev", label: "Revenue", values: fin.map((x) => x.revenue) }, { id: "ebitda", label: "EBITDA", values: fin.map((x) => x.ebitda) }]} format={(v) => `$${v.toFixed(1)}M`} label="Quarterly revenue and EBITDA" />
                </ChartShell>
              )}
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                <Panel>
                  <PanelHead title="Key events" />
                  <PanelBody>
                    <ActivityTimeline
                      items={[
                        { id: "e0", at: c.latestEvent.at, label: f.ago(c.latestEvent.at, now), title: c.latestEvent.title, state: c.status === "Covenant breach" ? "critical" : "complete" },
                        ...news.map((n) => ({ id: n.id, at: n.at, label: f.ago(n.at, now), title: n.title, source: n.source, state: "historical" as const })),
                        { id: "e9", at: daysAgo(20), label: f.date(daysAgo(20)), title: "September board meeting", source: "Board portal", state: "historical" },
                      ]}
                    />
                  </PanelBody>
                </Panel>
                <Panel>
                  <PanelHead title="Risks" icon={<ShieldAlert />} />
                  <PanelBody className="space-y-2">
                    {c.risks.length === 0 && alerts.length === 0 ? (
                      <p className="text-[13px] text-ink-3">No open risk flags.</p>
                    ) : (
                      <>
                        {alerts.map((a) => (
                          <Link key={a.id} href={`/app/alerts?id=${a.id}`} className="flex items-center gap-2 rounded-md border border-line px-2.5 py-2 text-[12px] hover:bg-hover">
                            <SeverityBadge severity={a.severity} />
                            <span className="min-w-0 flex-1 truncate text-ink">{a.title}</span>
                            <ArrowRight aria-hidden className="size-3 text-ink-4" />
                          </Link>
                        ))}
                        {c.risks.map((r) => (
                          <p key={r} className="flex items-center gap-2 text-[12px] text-ink-2">
                            <span aria-hidden className="size-1.5 rounded-full bg-warn" /> {r}
                          </p>
                        ))}
                      </>
                    )}
                  </PanelBody>
                </Panel>
              </div>
            </div>
          </div>
        )}

        {tab === "financials" &&
          (fin.length ? (
            <Panel>
              <PanelHead title="Quarterly financials" description="Management accounts, $M" />
              <DataTable
                id={`${c.id}-fin`}
                chrome="minimal"
                label="Quarterly financials"
                data={[...fin].reverse()}
                rowId={(x) => x.q}
                columns={[
                  { id: "q", header: "Quarter", value: (x) => x.q, width: 120 },
                  { id: "rev", header: "Revenue", value: (x) => x.revenue, align: "right", cell: (x) => <NumericCell value={x.revenue * 1e6} /> },
                  { id: "ebitda", header: "EBITDA", value: (x) => x.ebitda, align: "right", cell: (x) => <NumericCell value={x.ebitda * 1e6} /> },
                  { id: "margin", header: "Margin", value: (x) => x.margin, align: "right", cell: (x) => <NumericCell value={x.margin} kind="pct" muted /> },
                ]}
                empty={{ title: "No financials", body: "" }}
              />
            </Panel>
          ) : (
            <EmptyState title="No operating financials" body="Realised companies keep historical accounts in Documents." />
          ))}

        {tab === "performance" && (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            {positions.map((p) => (
              <ChartShell key={p.id} title={`${fundById(p.fundId)!.short} · ${p.instrument}`} subtitle={`MOIC ${f.multiple(investmentMoic(p))} · IRR ${f.pct(p.irr)} · QTD ${f.delta(p.qtdChange)}`} height={200}>
                <TrendChart x={["Q4 24", "Q1 25", "Q2 25", "Q3 25", "Q4 25", "Q1 26", "Q2 26", "Q3 26"]} series={[{ id: "fv", label: "Fair value", values: p.trend }]} format={(v) => `$${v.toFixed(1)}M`} label={`Fair value of ${p.id}`} />
              </ChartShell>
            ))}
            {positions.length === 0 && <EmptyState title="No open positions" body="This company has been fully realised." />}
          </div>
        )}

        {tab === "ownership" && (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
            <ChartShell className="lg:col-span-5" title="Cap table (fully diluted)" subtitle="OCTO funds vs other holders" height={340}>
              <DonutChart
                data={[...positions.filter((p) => p.ownership > 0).map((p) => ({ key: fundById(p.fundId)!.short, value: p.ownership })), { key: "Management", value: 8 }, { key: "Other holders", value: Math.max(0, 100 - 8 - positions.reduce((n, p) => n + p.ownership, 0)) }].filter((d) => d.value > 0)}
                label="Ownership"
                format={(v) => f.pct(v, 0)}
              />
            </ChartShell>
            <Panel className="lg:col-span-7">
              <PanelHead title="OCTO positions" />
              <DataTable
                id={`${c.id}-own`}
                chrome="minimal"
                label="Positions"
                data={positions}
                rowId={(p) => p.id}
                columns={[
                  { id: "fund", header: "Fund", value: (p) => fundById(p.fundId)!.name, width: 200 },
                  { id: "inst", header: "Instrument", value: (p) => p.instrument },
                  { id: "own", header: "Ownership", value: (p) => p.ownership, align: "right", cell: (p) => (p.ownership ? f.pct(p.ownership, 0) : "Debt") },
                  { id: "cost", header: "Cost", value: (p) => p.cost, align: "right", cell: (p) => <NumericCell value={p.cost} muted /> },
                  { id: "fv", header: "Fair value", value: (p) => p.fairValue, align: "right", cell: (p) => <NumericCell value={p.fairValue} /> },
                ]}
                empty={{ title: "No positions", body: "" }}
              />
            </Panel>
          </div>
        )}

        {tab === "valuation" && (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
            <Panel className="lg:col-span-7">
              <PanelHead title="Valuation history" description="Approved marks, $M" />
              <DataTable
                id={`${c.id}-val`}
                chrome="minimal"
                label="Valuation history"
                data={positions.flatMap((p) => p.trend.map((v, i) => ({ id: `${p.id}-${i}`, q: ["Q4 24", "Q1 25", "Q2 25", "Q3 25", "Q4 25", "Q1 26", "Q2 26", "Q3 26"][i], fund: fundById(p.fundId)!.short, value: v, method: p.instrument === "Senior loan" ? "Amortised cost" : i % 2 ? "Comparables" : "DCF", status: i === 7 && p.valuationAgeDays > 30 ? "Stale" : "Approved" }))).reverse()}
                rowId={(r) => r.id}
                columns={[
                  { id: "q", header: "Quarter", value: (r) => r.q, width: 100 },
                  { id: "fund", header: "Fund", value: (r) => r.fund },
                  { id: "method", header: "Method", value: (r) => r.method },
                  { id: "value", header: "Fair value", value: (r) => r.value, align: "right", cell: (r) => <NumericCell value={r.value * 1e6} /> },
                  { id: "status", header: "Status", value: (r) => r.status, cell: (r) => <WorkflowStatus state={r.status === "Stale" ? "Acknowledged" : "Approved"} /> },
                ]}
                empty={{ title: "No marks", body: "" }}
              />
            </Panel>
            <Panel className="lg:col-span-5">
              <PanelHead title="Current mark" />
              <PanelBody className="space-y-3">
                {positions.map((p) => (
                  <div key={p.id} className="rounded-lg border border-line p-3">
                    <div className="flex items-center justify-between">
                      <p className="text-[13px] font-medium text-ink">{fundById(p.fundId)!.short}</p>
                      <StatusBadge tone={p.valuationAgeDays > 30 ? "warn" : "ok"}>{p.valuationAgeDays > 30 ? `Stale · ${p.valuationAgeDays}d` : `Current · ${p.valuationAgeDays}d`}</StatusBadge>
                    </div>
                    <p className="mt-1 text-metric font-semibold tabular-nums text-ink">{f.money(p.fairValue)}</p>
                    <p className="text-[12px] text-ink-3">Approved {f.date(p.valuationDate)} · policy: IPEV, quarterly</p>
                  </div>
                ))}
                <Button className="w-full" onClick={() => toast({ tone: "info", title: "Mark requested", body: "Valuation team notified (demo)." })}>
                  Request new mark
                </Button>
              </PanelBody>
            </Panel>
          </div>
        )}

        {tab === "risks" && (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Panel>
              <PanelHead title="Alerts" />
              <PanelBody className="space-y-2">
                {alerts.length === 0 ? <p className="text-[13px] text-ink-3">No alerts for this company.</p> : alerts.map((a) => (
                  <Link key={a.id} href={`/app/alerts?id=${a.id}`} className="flex items-center gap-2 rounded-lg border border-line p-3 hover:bg-hover">
                    <SeverityBadge severity={a.severity} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] font-medium text-ink">{a.title}</span>
                      <span className="block text-[12px] text-ink-3">Observed {a.observed} vs {a.threshold} · {a.state}</span>
                    </span>
                  </Link>
                ))}
              </PanelBody>
            </Panel>
            <Panel>
              <PanelHead title="Covenants" />
              <DataTable
                id={`${c.id}-cov`}
                chrome="minimal"
                label="Covenants"
                data={[
                  { id: "DSCR", test: "Debt service cover", limit: "≥ 1.20×", actual: c.id === "CMP-0211" ? "1.14×" : "1.46×", ok: c.id !== "CMP-0211" },
                  { id: "LEV", test: "Net leverage", limit: "≤ 5.50×", actual: f.multiple(lev), ok: lev <= 5.5 },
                  { id: "CAPEX", test: "Capex cap", limit: "≤ $40M", actual: "$31M", ok: true },
                ]}
                rowId={(r) => r.id}
                columns={[
                  { id: "test", header: "Test", value: (r) => r.test, width: 180 },
                  { id: "limit", header: "Limit", value: (r) => r.limit, align: "right" },
                  { id: "actual", header: "Actual", value: (r) => r.actual, align: "right" },
                  { id: "ok", header: "Status", value: (r) => (r.ok ? "Pass" : "Breach"), cell: (r) => <StatusBadge tone={r.ok ? "ok" : "danger"}>{r.ok ? "Pass" : "Breach"}</StatusBadge> },
                ]}
                empty={{ title: "No covenants", body: "" }}
              />
            </Panel>
          </div>
        )}

        {tab === "documents" && <DataTable id={`${c.id}-docs`} label="Documents" data={docs} columns={docCols} rowId={(d) => d.id} restricted demo empty={{ title: "No documents", body: "Documents sync from the vault and board portal." }} />}

        {tab === "news" && (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
            {(news.length ? news : SIGNALS.filter((s) => s.kind === "Macro signal" || s.kind === "Market signal")).map((s) => (
              <article key={s.id} className="rounded-lg border border-line bg-surface p-4">
                <div className="flex items-center justify-between gap-2">
                  <Tag tone="accent">{s.kind}</Tag>
                  <span className="text-[11px] text-ink-4">{f.ago(s.at, now)}</span>
                </div>
                <p className="mt-2 text-[13px] font-semibold text-ink">{s.title}</p>
                <p className="mt-1 text-[12px] text-ink-3">{s.body}</p>
                <p className="mt-2 text-[11px] text-ink-4">Source: {s.source}</p>
              </article>
            ))}
            {!news.length && <p className="text-[12px] text-ink-3 md:col-span-2 xl:col-span-3">No company-specific news matched this quarter; showing market signals.</p>}
          </div>
        )}

        {tab === "activity" && (
          <Panel>
            <PanelHead title="Activity" />
            <PanelBody>
              <ActivityTimeline
                now={now}
                items={([
                  { id: "a1", at: c.latestEvent.at, label: f.ago(c.latestEvent.at, now), title: c.latestEvent.title, state: "complete" },
                  ...alerts.map((a) => ({ id: a.id, at: a.triggeredAt, label: f.ago(a.triggeredAt, now), title: `Alert raised: ${a.title}`, detail: `Rule ${a.ruleId}`, actor: a.owner, source: "Alert rules", state: (a.severity === "critical" ? "critical" : "attention") as "critical" | "attention" })),
                  { id: "a3", at: daysAgo(20), label: f.date(daysAgo(20)), title: "Board pack uploaded", source: "Document vault", state: "historical" },
                  ...(positions[0] ? [{ id: "a4", at: positions[0].valuationDate, label: f.date(positions[0].valuationDate), title: "Valuation approved", detail: f.money(positions[0].fairValue), actor: "Valuation committee", source: "IBOR valuation log", state: "complete" as const }] : []),
                ] as ActivityItem[]).sort((a, b) => b.at.localeCompare(a.at))}
              />
            </PanelBody>
          </Panel>
        )}

        {tab === "lineage" && (
          <Panel>
            <PanelHead title="Fair value lineage" description="How this company's fair value reaches fund and portfolio NAV" />
            <PanelBody>
              <ol className="grid grid-cols-1 gap-3 md:grid-cols-4" aria-label="Lineage layers">
                {NAV_LINEAGE.map((layer, li) => (
                  <li key={layer.layer} className="relative">
                    <p className="text-label uppercase text-ink-4">{layer.layer}</p>
                    <ul className="mt-2 space-y-1.5">
                      {(li === 3 ? [`${c.name} fair value`, ...layer.nodes.slice(0, 2)] : layer.nodes).map((n) => (
                        <li key={n} className="rounded-md border border-line bg-subtle px-2.5 py-1.5 text-[12px] text-ink-2">
                          {n}
                        </li>
                      ))}
                    </ul>
                    {li < 3 && <ArrowRight aria-hidden className="absolute -right-3 top-8 hidden size-4 text-ink-4 md:block" />}
                  </li>
                ))}
              </ol>
              <p className="mt-4 flex items-center gap-1.5 text-[12px] text-ink-3">
                <Lock aria-hidden className="size-3" /> Positions are derived from the transaction ledger; values are never written directly.
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                {positions.map((p) => (
                  <EntityChip key={p.id} type="Investment" name={`${p.id} · ${fundById(p.fundId)!.short}`} href={`/app/investments?focus=${p.id}`} />
                ))}
              </div>
            </PanelBody>
          </Panel>
        )}
      </PageBody>

      <KpiMetricDrawer metric={kpi} onClose={() => setKpi(null)} onLineage={(m) => (setKpi(null), setLineage(m))} />
      <ConfirmDialog
        open={markOpen}
        title="Request a new valuation mark?"
        body={
          <>
            <p>
              This asks the valuation team for a fresh fair-value mark on {c.name}. The latest approved mark is from {positions[0] ? f.date(positions[0].valuationDate) : "—"}
              {positions[0] ? ` (${positions[0].valuationAgeDays} days old; policy is 30)` : ""}. Until a new mark is approved, NAV and returns keep using it.
            </p>
            <p className="mt-2 text-ink-3">The request lands in the valuation team’s queue in Workflows. Demo: nothing is sent.</p>
          </>
        }
        confirmLabel="Send request"
        onCancel={() => setMarkOpen(false)}
        onConfirm={() => {
          setMarkOpen(false);
          toast({ tone: "ok", title: "Mark requested", body: `${c.name} · queued for the valuation team (demo).` });
        }}
      />
      <LineageDrawer open={!!lineage} onClose={() => setLineage(null)} title={lineage?.label ?? ""} value={lineage ? fmt(lineage) : ""} provenance={lineage?.provenance ?? null} />
    </>
  );
}
