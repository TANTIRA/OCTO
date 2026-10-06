"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowUpRight } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { useInvestments } from "@/lib/data/queries";
import { AS_OF, PORTFOLIO_METRICS, companyById, currencyOf, fundById, investmentMoic, type Investment, type Metric } from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { MetricCard, MetricGrid, useMetricValue } from "@/components/metric/metric-card";
import { LineageDrawer } from "@/components/metric/metric-lineage";
import { KpiMetricDrawer, type PageAction } from "@/components/metric/kpi-drawer";
import { TrendChart } from "@/components/chart/line-chart";
import { DataTable, type Column } from "@/components/data/data-table";
import { DeltaCell, EntityCell, FreshnessCell, NumericCell, ProvenanceCell, SparklineCell, StatusCell } from "@/components/data/cells";
import { Button, LinkButton } from "@/components/ui/button";
import type { ViewState } from "@/components/data/table-state";
import { StatusBadge, type Tone } from "@/components/ui/badge";
import { Sheet } from "@/components/ui/overlay";
import { FreshnessBadge, InlineAlert, useToast } from "@/components/feedback";
import { ObjectMetadata } from "@/components/object/object";
import { useBreadcrumb } from "@/components/shell/shell-context";

/** URL params that pre-filter the grid; each names a facet column below. */
const FILTER_PARAMS = ["sector", "geo", "strategy", "fund", "currency", "risk"];

const RISK_TONE: Record<Investment["riskStatus"], Tone> = { "On track": "ok", Watch: "warn", "At risk": "danger" };
const ownBand = (o: number) => (o === 0 ? "Debt" : o >= 50 ? "Control (≥50%)" : o >= 20 ? "Significant (20–50%)" : "Minority (<20%)");
const ageBand = (d: number) => (d <= 30 ? "Current (≤30d)" : d <= 45 ? "Due (31–45d)" : "Stale (>45d)");

/**
 * Investment explorer (plan §13): filter-first grid over every position —
 * fund, strategy, stage, geography, sector, ownership, realisation, valuation
 * freshness and risk — with grouping, pinning, saved views, export, and a
 * detail sheet that keeps the grid state.
 */
export function InvestmentsView() {
  const f = useFormat();
  const router = useRouter();
  const params = useSearchParams();
  const toast = useToast();
  const fmt = useMetricValue();
  useBreadcrumb(null);
  const inv = useInvestments();
  const rows = inv.data ?? [];
  const [lineage, setLineage] = useState<Metric | null>(null);
  const [kpi, setKpi] = useState<Metric | null>(null);
  const [tableCommand, setTableCommand] = useState<{ key: number; state: Partial<ViewState> }>();
  const focus = params.get("focus");

  // Drill-ins from other pages (e.g. a Portfolio allocation slice): ?sector=Consumer filters the grid.
  const drill = FILTER_PARAMS.map((k) => [k, params.get(k)] as const).filter((e): e is readonly [string, string] => !!e[1]);
  const drillKey = drill.map(([k, v]) => `${k}=${v}`).join("&");
  useEffect(() => {
    if (!drillKey) return;
    setTableCommand({ key: Date.now(), state: { facets: Object.fromEntries(drill.map(([k, v]) => [k, [v]])), sort: [{ id: "fv", desc: true }] } });
    // The filter now lives in the table (as removable chips); drop it from the URL so a
    // refresh after the user clears it does not bring it back.
    const rest = new URLSearchParams(params.toString());
    FILTER_PARAMS.forEach((k) => rest.delete(k));
    router.replace(`/app/investments${rest.size ? `?${rest}` : ""}`, { scroll: false });
    // drill is derived from drillKey
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drillKey]);
  const open = rows.find((r) => r.id === focus) ?? null;
  const setOpen = (id: string | null) => {
    const p = new URLSearchParams(params.toString());
    if (id) p.set("focus", id);
    else p.delete("focus");
    router.replace(`/app/investments${p.size ? `?${p}` : ""}`, { scroll: false });
  };

  const fv = rows.reduce((n, i) => n + i.fairValue, 0);
  const cost = rows.reduce((n, i) => n + i.cost, 0);
  const stale = rows.filter((i) => i.valuationAgeDays > 30).length;

  const summary: Metric[] = [
    { ...PORTFOLIO_METRICS[4], label: "Cost", value: cost },
    { id: "fv", label: "Fair value", value: fv, format: "money", comparison: `${rows.length} positions`, provenance: PORTFOLIO_METRICS[0].provenance },
    { id: "moic", label: "Gross MOIC", value: cost ? (fv + rows.reduce((n, i) => n + i.realized, 0)) / cost : 0, format: "multiple", comparison: "fair value + realised ÷ cost", provenance: { ...PORTFOLIO_METRICS[0].provenance, formula: "(Σ FV + Σ realised) ÷ Σ cost", inputs: [{ label: "Fair value", value: f.money(fv) }, { label: "Cost", value: f.money(cost) }] } },
    { id: "stale", label: "Stale valuations", value: stale, format: "count", comparison: "past the 30-day mark policy", upIsGood: false, provenance: { ...PORTFOLIO_METRICS[0].provenance, formula: "count(valuation age > 30 days)", inputs: rows.filter((i) => i.valuationAgeDays > 30).map((i) => ({ label: companyById(i.companyId)!.name, value: `${i.valuationAgeDays} days` })) } },
  ];

  // Each summary KPI answers "which positions drive this?" by re-ordering the grid below.
  const KPI_VIEW: Record<string, { label: string; state: Partial<ViewState> }> = {
    invested: { label: "Sort positions by cost", state: { sort: [{ id: "cost", desc: true }] } },
    fv: { label: "Sort positions by fair value", state: { sort: [{ id: "fv", desc: true }] } },
    moic: { label: "Sort positions by MOIC", state: { sort: [{ id: "moic", desc: true }] } },
    stale: { label: "Show stale positions", state: { facets: { valuation: ["Due (31–45d)", "Stale (>45d)"] }, sort: [{ id: "valuation", desc: true }] } },
  };
  const kpiAction = (m: Metric): PageAction | null => {
    const v = KPI_VIEW[m.id];
    return v ? { label: v.label, run: () => setTableCommand({ key: Date.now(), state: v.state }) } : null;
  };

  const cols: Column<Investment>[] = useMemo(
    () => [
      { id: "company", header: "Investment", width: 250, hideable: false, value: (i) => companyById(i.companyId)!.name, cell: (i) => { const c = companyById(i.companyId)!; return <EntityCell name={c.name} sub={`${i.id} · ${i.instrument}`} href={`/app/companies/${c.id.toLowerCase()}`} />; } },
      { id: "fund", header: "Fund", width: 140, value: (i) => fundById(i.fundId)!.short, facet: true, groupable: true },
      { id: "strategy", header: "Strategy", value: (i) => fundById(i.fundId)!.strategy, facet: true, groupable: true },
      { id: "stage", header: "Stage", value: (i) => companyById(i.companyId)!.stage, facet: true, groupable: true, defaultHidden: true },
      { id: "sector", header: "Sector", value: (i) => companyById(i.companyId)!.sector, facet: true, groupable: true },
      { id: "geo", header: "Geography", value: (i) => companyById(i.companyId)!.geography, facet: true, groupable: true },
      { id: "currency", header: "Currency", value: (i) => currencyOf(i.companyId), facet: true, groupable: true, defaultHidden: true },
      { id: "own", header: "Ownership", value: (i) => ownBand(i.ownership), sortValue: (i) => i.ownership, facet: true, cell: (i) => (i.ownership ? <span className="tabular-nums">{f.pct(i.ownership, 0)}</span> : <span className="text-ink-4">Debt</span>), align: "right" },
      { id: "realization", header: "Realisation", value: (i) => i.realization, facet: true, defaultHidden: true },
      { id: "cost", header: "Cost", value: (i) => i.cost, align: "right", cell: (i) => <NumericCell value={i.cost} muted />, aggregate: (r) => f.money(r.reduce((n, i) => n + i.cost, 0)) },
      { id: "fv", header: "Fair value", value: (i) => i.fairValue, align: "right", cell: (i) => <NumericCell value={i.fairValue} />, aggregate: (r) => f.money(r.reduce((n, i) => n + i.fairValue, 0)) },
      { id: "moic", header: "MOIC", value: (i) => investmentMoic(i), align: "right", cell: (i) => <NumericCell value={investmentMoic(i)} kind="multiple" /> },
      { id: "irr", header: "IRR", value: (i) => i.irr, align: "right", cell: (i) => <NumericCell value={i.irr} kind="pct" /> },
      { id: "qtd", header: "QTD", value: (i) => i.qtdChange, align: "right", cell: (i) => <DeltaCell value={i.qtdChange} /> },
      { id: "trend", kind: "trend", header: "8Q trend", value: (i) => i.trend[7], sortable: false, cell: (i) => <SparklineCell values={i.trend} risk={i.riskStatus} /> },
      { id: "valuation", header: "Mark age", value: (i) => ageBand(i.valuationAgeDays), sortValue: (i) => i.valuationAgeDays, facet: true, align: "right", cell: (i) => <FreshnessCell ageDays={i.valuationAgeDays} targetDays={30} /> },
      { id: "risk", header: "Risk", value: (i) => i.riskStatus, facet: true, groupable: true, cell: (i) => <StatusCell tone={RISK_TONE[i.riskStatus]}>{i.riskStatus}</StatusCell> },
      { id: "lineage", header: "Lineage", value: () => null, sortable: false, align: "center", cell: (i) => <ProvenanceCell label={companyById(i.companyId)!.name} onOpen={() => setLineage({ id: i.id, label: `${companyById(i.companyId)!.name} fair value`, value: i.fairValue, format: "money", comparison: "", provenance: { formula: "Approved mark × quantity (equity) or amortised cost + accrued (debt)", inputs: [{ label: "Approved mark", value: f.date(i.valuationDate) }, { label: "Cost", value: f.money(i.cost) }], asOf: i.valuationDate, sourceSystem: "IBOR · valuation log", sourceDocument: `Valuation memo ${i.id}`, transformation: "Valuation policy v4 (IPEV)", version: "FV v1.2" } })} /> },
    ],
    [f],
  );

  return (
    <>
      <PageHeader variant="list" eyebrow="Invest" title="Investments" description="Every position across funds. Filter first, then drill into the company." meta={<FreshnessBadge state="demo" asOf={`marks as of ${f.date(AS_OF)}`} />} />
      <PageBody className="space-y-6">
        <MetricGrid cols={4}>
          {summary.map((m) => (
            <MetricCard key={m.id} metric={m} variant="compact" onOpen={setKpi} state={inv.isLoading ? "loading" : "ready"} />
          ))}
        </MetricGrid>
        {stale > 0 && <InlineAlert tone="warn">{stale} positions have no approved mark in the last 30 days. Their fair values are shown but flagged in the Mark age column.</InlineAlert>}
        <DataTable
          id="investments"
          label="Investments"
          data={rows}
          status={inv.isLoading ? "loading" : "ready"}
          columns={cols}
          rowId={(i) => i.id}
          command={tableCommand}
          demo
          totals
          selectable
          activeRowId={focus}
          onRowOpen={(i) => setOpen(i.id)}
          exportName="octo-investments"
          searchPlaceholder="Search company, fund, ID…"
          views={[
            { id: "all", name: "All positions", state: { sort: [{ id: "fv", desc: true }] } },
            { id: "watch", name: "Needs attention", state: { facets: { risk: ["At risk", "Watch"] }, sort: [{ id: "qtd", desc: false }] } },
            { id: "stale", name: "Stale marks", state: { facets: { valuation: ["Due (31–45d)", "Stale (>45d)"] }, sort: [{ id: "valuation", desc: true }] } },
            { id: "sector", name: "By sector", state: { groupBy: "sector", sort: [{ id: "fv", desc: true }] } },
          ]}
          rowActions={(i) => [
            { label: "Open company", onSelect: () => router.push(`/app/companies/${i.companyId.toLowerCase()}`) },
            { label: "Open fund", onSelect: () => router.push(`/app/funds/${i.fundId.toLowerCase()}`) },
            { label: "Request valuation mark", onSelect: () => toast({ tone: "info", title: "Mark requested", body: `${companyById(i.companyId)!.name} · sent to the valuation team (demo).` }) },
          ]}
          bulkActions={(sel, clear) => (
            <Button
              size="sm"
              onClick={() => {
                toast({ tone: "info", title: `Mark requested for ${sel.length} positions`, body: "Sent to the valuation team (demo)." });
                clear();
              }}
            >
              Request marks
            </Button>
          )}
          empty={{ title: "No positions", body: "Positions appear once capital is deployed and booked in the IBOR." }}
        />
      </PageBody>

      <Sheet open={!!open} onClose={() => setOpen(null)} eyebrow={open ? `Investment · ${open.id}` : ""} title={open ? `${companyById(open.companyId)!.name}` : ""}>
        {open && <InvestmentDetail i={open} />}
      </Sheet>
      <KpiMetricDrawer metric={kpi} onClose={() => setKpi(null)} onLineage={(m) => (setKpi(null), setLineage(m))} pageAction={kpiAction} />
      <LineageDrawer open={!!lineage} onClose={() => setLineage(null)} title={lineage?.label ?? ""} value={lineage ? fmt(lineage) : ""} provenance={lineage?.provenance ?? null} />
    </>
  );
}

function InvestmentDetail({ i }: { i: Investment }) {
  const f = useFormat();
  const c = companyById(i.companyId)!;
  const fund = fundById(i.fundId)!;
  const quarters = ["Q4 24", "Q1 25", "Q2 25", "Q3 25", "Q4 25", "Q1 26", "Q2 26", "Q3 26"];
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap gap-2">
        <StatusBadge tone={RISK_TONE[i.riskStatus]}>{i.riskStatus}</StatusBadge>
        <StatusBadge tone="neutral">{i.instrument}</StatusBadge>
        <StatusBadge tone={i.valuationAgeDays > 30 ? "warn" : "ok"}>Marked {i.valuationAgeDays}d ago</StatusBadge>
      </div>
      <div className="grid grid-cols-3 gap-3">
        {[
          ["Fair value", f.money(i.fairValue)],
          ["MOIC", f.multiple(investmentMoic(i))],
          ["IRR", f.pct(i.irr)],
        ].map(([k, v]) => (
          <div key={k} className="rounded-lg border border-line p-3">
            <p className="text-[12px] text-ink-3">{k}</p>
            <p className="mt-1 text-metric font-semibold tabular-nums text-ink">{v}</p>
          </div>
        ))}
      </div>
      <div className="h-44">
        <TrendChart x={quarters} series={[{ id: "fv", label: "Fair value", values: i.trend }]} format={(v) => `$${v.toFixed(1)}M`} label="Fair value, last 8 quarters ($M)" />
      </div>
      <ObjectMetadata
        items={[
          { label: "Fund", value: fund.name },
          { label: "Entry", value: f.date(i.entryDate) },
          { label: "Cost", value: f.moneyFull(i.cost) },
          { label: "Fair value", value: f.moneyFull(i.fairValue) },
          { label: "Realised", value: f.moneyFull(i.realized) },
          { label: "Ownership", value: i.ownership ? f.pct(i.ownership, 0) : "Debt instrument" },
          { label: "Last mark", value: f.date(i.valuationDate) },
          { label: "QTD change", value: f.delta(i.qtdChange) },
        ]}
      />
      <div className="flex flex-wrap gap-2">
        <LinkButton href={`/app/companies/${c.id.toLowerCase()}?fund=${fund.slug}`} variant="primary" size="sm">
          Open {c.name} <ArrowUpRight />
        </LinkButton>
        <LinkButton href={`/app/funds/${fund.slug}`} size="sm">
          Open {fund.short}
        </LinkButton>
      </div>
    </div>
  );
}
