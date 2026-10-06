"use client";

import { useState } from "react";
import { notFound, useRouter, useSearchParams } from "next/navigation";
import { Download, GitBranch, History } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { useFund, useInvestments } from "@/lib/data/queries";
import { AS_OF, DEMO_NOW, companyById, fundBridge, fundCashFlows, fundMetrics, fundNavSeries, fundTvpi, investmentMoic, type Investment, type Metric } from "@/lib/demo";
import { PageBody } from "@/components/page/page-header";
import { Panel, PanelBody, PanelHead } from "@/components/page/panel";
import { ObjectHeader, ObjectLinks, ObjectMetadata } from "@/components/object/object";
import { MetricCard, MetricGrid, useMetricValue } from "@/components/metric/metric-card";
import { LineageDrawer } from "@/components/metric/metric-lineage";
import { KpiMetricDrawer } from "@/components/metric/kpi-drawer";
import { ChartShell } from "@/components/chart/chart-shell";
import { BarChart, RankingBars } from "@/components/chart/bar-chart";
import { WaterfallChart } from "@/components/chart/waterfall-chart";
import { DonutChart } from "@/components/chart/donut-chart";
import { Legend } from "@/components/chart/core";
import { ActivityTimeline, type ActivityItem } from "@/components/chart/timeline";
import { NavChart } from "@/components/chart/nav-chart";
import { DataTable, type Column } from "@/components/data/data-table";
import { DeltaCell, EntityCell, NumericCell, SparklineCell, StatusCell } from "@/components/data/cells";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/badge";
import { Tabs } from "@/components/ui/controls";
import { FreshnessBadge, MetricSkeleton, useToast } from "@/components/feedback";
import { useBreadcrumb } from "@/components/shell/shell-context";
import { FUND_TONE } from "./funds";

type Tab = "overview" | "holdings" | "attribution" | "activity";
const now = new Date(DEMO_NOW);

/**
 * Fund object page (plan §12): KPI band, NAV trajectory, cash-flow timeline,
 * value bridge, sector/geography attribution, underlying investments, and
 * drill-down fund → investment → company that keeps the fund in context.
 */
export function FundDetail({ id }: { id: string }) {
  const f = useFormat();
  const router = useRouter();
  const params = useSearchParams();
  const toast = useToast();
  const fmt = useMetricValue();
  const fund = useFund(id);
  const inv = useInvestments(fund.data?.id);
  const [lineage, setLineage] = useState<Metric | null>(null);
  const [kpi, setKpi] = useState<Metric | null>(null);
  const tab = (params.get("tab") as Tab) ?? "overview";
  const x = fund.data;

  useBreadcrumb(x ? [{ label: "Invest" }, { label: "Funds", href: "/app/funds" }, { label: x.short }] : null, x ? { type: "Fund", name: x.name, href: `/app/funds/${x.slug}` } : undefined);

  if (fund.isSuccess && !x) notFound();
  if (!x) {
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

  const rows = inv.data ?? [];
  const metrics = fundMetrics(x);
  const nav = fundNavSeries(x);
  const flows = fundCashFlows(x);
  const bridge = fundBridge(x);
  const opening = bridge[0].value;
  const closing = bridge[bridge.length - 1].value;
  const q3calls = flows[flows.length - 1].calls;
  const q3dists = flows[flows.length - 1].dists;

  const fmtM = (v: number) => `${v < 0 ? "−" : ""}$${Math.abs(v).toFixed(1)}M`;
  // Quarter-end NAV path with a rebased public benchmark, for the shared NavChart.
  const navPoints = nav.map((p, i) => ({ q: p.q, nav: p.nav, benchmark: Math.round(nav[0].nav * Math.pow(1.021, i) * 10) / 10 }));
  const at = (h: number) => new Date(now.getTime() - h * 3_600_000).toISOString();
  const activity: ActivityItem[] = [
    { id: "f1", at: at(0.4), label: f.ago(at(0.4), now), title: "Q3 NAV struck", detail: `${f.money(x.nav)} closing NAV under definition v2.1`, actor: "Q3 NAV model", source: "IBOR valuations", state: "complete" },
    { id: "f2", at: at(3), label: f.ago(at(3), now), title: "Administrator file awaiting tie-out", detail: "Cash statement for 30 Sep not yet matched to the ledger", actor: "Fund accounting", source: "Apex Fund Services", state: "pending" },
    { id: "f3", at: at(30), label: f.ago(at(30), now), title: `Capital call #14 settled`, detail: `${fmtM(q3calls)} received from LPs`, actor: "Fund accounting", source: "Bank statement", state: "complete" },
    { id: "f4", at: at(52), label: f.ago(at(52), now), title: "Stale mark flagged", detail: "One position has no approved valuation in 45 days", actor: "OCTO", source: "Valuation log", state: "attention" },
    { id: "f5", at: at(76), label: f.ago(at(76), now), title: "IC memo approved", detail: "Follow-on approved by the investment committee", actor: "M. Sari", source: "Workflows", state: "complete" },
    { id: "f6", at: at(120), label: f.ago(at(120), now), title: "Cash break resolved", detail: "FX settlement timing difference, accepted IBOR", actor: "Fund accounting", source: "Reconciliation", state: "complete" },
    { id: "f7", at: at(400), label: f.date(at(400)), title: "Q2 NAV struck", detail: `${fmtM(opening)} closing NAV`, actor: "Q2 NAV model", source: "IBOR valuations", state: "historical" },
    { id: "f8", at: at(1500), label: f.date(at(1500)), title: `${x.short} distribution paid`, detail: `${fmtM(q3dists)} to LPs`, actor: "Fund accounting", source: "Bank statement", state: "historical" },
  ];

  const bySector = new Map<string, number>();
  const byGeo = new Map<string, number>();
  const contrib = new Map<string, number>();
  for (const i of rows) {
    const c = companyById(i.companyId)!;
    bySector.set(c.sector, (bySector.get(c.sector) ?? 0) + i.fairValue);
    byGeo.set(c.geography, (byGeo.get(c.geography) ?? 0) + i.fairValue);
    contrib.set(c.sector, (contrib.get(c.sector) ?? 0) + (i.fairValue * i.qtdChange) / 100);
  }
  const sum = rows.reduce((n, i) => n + i.fairValue, 0) || 1;

  const cols: Column<Investment>[] = [
    { id: "company", header: "Company", width: 250, hideable: false, value: (i) => companyById(i.companyId)!.name, cell: (i) => { const c = companyById(i.companyId)!; return <EntityCell name={c.name} sub={`${c.sector} · ${c.geography}`} href={`/app/companies/${c.id.toLowerCase()}?fund=${x.slug}`} />; } },
    { id: "instrument", header: "Instrument", value: (i) => i.instrument, facet: true },
    { id: "entry", header: "Entry", value: (i) => i.entryDate, cell: (i) => f.date(i.entryDate), align: "right" },
    { id: "cost", header: "Cost", value: (i) => i.cost, align: "right", cell: (i) => <NumericCell value={i.cost} muted />, aggregate: (r) => f.money(r.reduce((n, i) => n + i.cost, 0)) },
    { id: "fv", header: "Fair value", value: (i) => i.fairValue, align: "right", cell: (i) => <NumericCell value={i.fairValue} />, aggregate: (r) => f.money(r.reduce((n, i) => n + i.fairValue, 0)) },
    { id: "moic", header: "MOIC", value: (i) => investmentMoic(i), align: "right", cell: (i) => <NumericCell value={investmentMoic(i)} kind="multiple" /> },
    { id: "irr", header: "IRR", value: (i) => i.irr, align: "right", cell: (i) => <NumericCell value={i.irr} kind="pct" /> },
    { id: "weight", header: "Weight", value: (i) => (i.fairValue / sum) * 100, align: "right", cell: (i) => <NumericCell value={(i.fairValue / sum) * 100} kind="pct" muted /> },
    { id: "qtd", header: "QTD", value: (i) => i.qtdChange, align: "right", cell: (i) => <DeltaCell value={i.qtdChange} /> },
    { id: "trend", kind: "trend", header: "Trend", value: (i) => i.trend[7], sortable: false, cell: (i) => <SparklineCell values={i.trend} risk={i.riskStatus} /> },
    { id: "risk", header: "Risk", value: (i) => i.riskStatus, facet: true, cell: (i) => <StatusCell tone={i.riskStatus === "On track" ? "ok" : i.riskStatus === "Watch" ? "warn" : "danger"}>{i.riskStatus}</StatusCell> },
  ];

  const setTab = (t: Tab) => router.replace(`/app/funds/${x.slug}${t === "overview" ? "" : `?tab=${t}`}`, { scroll: false });

  return (
    <>
      <ObjectHeader
        type="Fund"
        name={x.name}
        status={<StatusBadge tone={FUND_TONE[x.status]}>{x.status}</StatusBadge>}
        classification={[x.strategy, `Vintage ${x.vintage}`, x.geography]}
        facts={[
          { label: "Manager", value: x.manager },
          { label: "Committed", value: f.money(x.committed) },
          { label: "NAV", value: f.money(x.nav) },
          { label: "TVPI", value: f.multiple(fundTvpi(x)) },
          { label: "Positions", value: rows.length },
        ]}
        freshness={<FreshnessBadge state="demo" asOf={f.date(AS_OF)} />}
        permission="Partners, IR, fund accounting"
        actions={
          <>
            <Button onClick={() => setLineage(metrics[2])}>
              <GitBranch /> NAV lineage
            </Button>
            <Button variant="primary" onClick={() => toast({ tone: "info", title: "LP report queued", body: "Opens in Reports as a draft (demo)." })}>
              <Download /> LP report
            </Button>
          </>
        }
        tabs={
          <Tabs<Tab>
            label="Fund sections"
            value={tab}
            onChange={setTab}
            className="border-b-0"
            items={[
              { value: "overview", label: "Overview" },
              { value: "holdings", label: "Holdings", count: rows.length },
              { value: "attribution", label: "Attribution" },
              { value: "activity", label: "Activity" },
            ]}
          />
        }
      />

      <PageBody className="space-y-6">
        {tab === "overview" && (
          <>
            <MetricGrid cols={4}>
              {metrics.map((m) => (
                <MetricCard key={m.id} metric={m} variant="compact" onOpen={setKpi} />
              ))}
            </MetricGrid>
            <NavChart title="NAV trajectory" points={navPoints} benchmark={false} onLineage={() => setLineage(metrics[2])} />
            <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
              <ChartShell
                className="xl:col-span-7"
                title="Q3 value bridge"
                subtitle={`Opening ${fmtM(opening)} + calls − distributions ± valuation ± FX = closing ${fmtM(closing)}`}
                legend={<Legend items={[{ label: "Opening / closing", color: "var(--color-mark-neutral)" }, { label: "Increase", color: "var(--color-gain)" }, { label: "Decrease", color: "var(--color-loss)" }]} />}
                height={360}
                expandable={false}
                exportData={{ filename: `${x.slug}-bridge`, head: ["Step", "$M"], rows: bridge.map((b) => [b.label, b.value]) }}
              >
                <WaterfallChart data={bridge} label={`${x.name} Q3 value bridge`} format={fmtM} axisFormat={(v) => `$${Math.round(v)}M`} />
              </ChartShell>
              <Panel className="xl:col-span-5">
                <PanelHead title="Recent activity" icon={<History />} toolbar={<Button size="sm" variant="ghost" onClick={() => setTab("activity")}>All activity</Button>} />
                <PanelBody fill={360} label="Recent fund activity">
                  <ActivityTimeline items={activity.slice(0, 4)} now={now} />
                </PanelBody>
              </Panel>
            </div>
            <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
              <ChartShell
                className="xl:col-span-8"
                title="Cash-flow timeline"
                subtitle="Capital calls and distributions per quarter, $M"
                legend={<Legend items={[{ label: "Capital calls", color: "var(--color-chart-1)" }, { label: "Distributions", color: "var(--color-chart-2)" }]} />}
                exportData={{ filename: `${x.slug}-cashflows`, head: ["Quarter", "Calls", "Distributions"], rows: flows.map((c) => [c.q, c.calls, c.dists]) }}
                height={220}
              >
                <BarChart x={flows.map((c) => c.q)} series={[{ id: "calls", label: "Capital calls", values: flows.map((c) => c.calls) }, { id: "dists", label: "Distributions", values: flows.map((c) => c.dists) }]} format={(v) => `$${v.toFixed(1)}M`} label="Cash flows" />
              </ChartShell>
              <Panel className="xl:col-span-4">
                <PanelHead title="Fund facts" />
                <PanelBody>
                  <ObjectMetadata
                    items={[
                      { label: "Legal name", value: `${x.name}, L.P.` },
                      { label: "Domicile", value: x.geography === "Global" ? "Cayman Islands" : "Singapore" },
                      { label: "Currency", value: "USD" },
                      { label: "Vintage", value: x.vintage },
                      { label: "Called", value: `${f.money(x.called)} (${f.pct((x.called / x.committed) * 100, 0)})` },
                      { label: "Dry powder", value: f.money(x.committed - x.called) },
                      { label: "Gross IRR", value: f.pct(x.grossIrr) },
                      { label: "Cash", value: f.money(x.cash) },
                    ]}
                  />
                </PanelBody>
              </Panel>
            </div>
            <Panel>
              <PanelHead title="Largest positions" description="Drill into a company; the fund stays in the breadcrumb" />
              <PanelBody>
                <ObjectLinks links={[...rows].sort((a, b) => b.fairValue - a.fairValue).slice(0, 5).map((i) => ({ type: "Company", name: companyById(i.companyId)!.name, href: `/app/companies/${i.companyId.toLowerCase()}?fund=${x.slug}`, meta: `${f.money(i.fairValue)} · ${f.pct((i.fairValue / sum) * 100)}` }))} />
              </PanelBody>
            </Panel>
          </>
        )}

        {tab === "holdings" && (
          <DataTable
            id={`fund-${x.slug}-holdings`}
            label={`${x.name} holdings`}
            data={rows}
            status={inv.isLoading ? "loading" : "ready"}
            columns={cols}
            rowId={(i) => i.id}
            demo
            totals
            onRowOpen={(i) => router.push(`/app/companies/${i.companyId.toLowerCase()}?fund=${x.slug}`)}
            exportName={`${x.slug}-holdings`}
            empty={{ title: "No positions yet", body: "Positions appear once the fund deploys capital." }}
          />
        )}

        {tab === "attribution" && (
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
            <ChartShell title="Sector allocation" subtitle="Share of fair value" height="auto">
              <DonutChart data={[...bySector].map(([key, value]) => ({ key, value })).sort((a, b) => b.value - a.value)} label="Sector allocation" format={(v) => f.money(v)} />
            </ChartShell>
            <Panel>
              <PanelHead title="Sector attribution" description="Contribution to QTD value change" />
              <PanelBody>
                <RankingBars label="Sector attribution" format={(v) => f.delta(v, "$")} items={[...contrib].map(([label, value]) => ({ label, value, color: value >= 0 ? "var(--color-gain)" : "var(--color-loss)" })).sort((a, b) => b.value - a.value)} />
              </PanelBody>
            </Panel>
            <Panel>
              <PanelHead title="Geography attribution" description="Share of fair value" />
              <PanelBody>
                <RankingBars label="Geography attribution" format={(v) => f.pct((v / sum) * 100)} items={[...byGeo].map(([label, value]) => ({ label, value, sub: f.money(value) })).sort((a, b) => b.value - a.value)} />
              </PanelBody>
            </Panel>
          </div>
        )}

        {tab === "activity" && (
          <Panel>
            <PanelHead title="Activity" description="Fund events in order, newest first. Each entry shows its state, owner and source." icon={<History />} />
            <PanelBody>
              <ActivityTimeline items={activity} now={now} />
            </PanelBody>
          </Panel>
        )}
      </PageBody>

      <KpiMetricDrawer metric={kpi} onClose={() => setKpi(null)} onLineage={(m) => (setKpi(null), setLineage(m))} />
      <LineageDrawer open={!!lineage} onClose={() => setLineage(null)} title={lineage?.label ?? ""} value={lineage ? fmt(lineage) : ""} provenance={lineage?.provenance ?? null} />
    </>
  );
}
