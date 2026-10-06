"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft, RefreshCw } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { useSources } from "@/lib/data/queries";
import { DEMO_NOW, MAPPINGS, PORTFOLIO_METRICS, type DataSource, type Mapping, type Metric } from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { Panel, PanelBody, PanelHead } from "@/components/page/panel";
import { MetricCard, MetricGrid } from "@/components/metric/metric-card";
import { ChartShell } from "@/components/chart/chart-shell";
import { BarChart } from "@/components/chart/bar-chart";
import { Legend } from "@/components/chart/core";
import { LineageExplorer } from "@/components/data/lineage-explorer";
import { DataTable, type Column } from "@/components/data/data-table";
import { NumericCell, SparklineCell, StatusCell } from "@/components/data/cells";
import { Button, LinkButton } from "@/components/ui/button";
import { StatusBadge, type Tone } from "@/components/ui/badge";
import { Tabs } from "@/components/ui/controls";
import { Sheet } from "@/components/ui/overlay";
import { FreshnessBadge, InlineAlert, useToast } from "@/components/feedback";
import { ObjectMetadata } from "@/components/object/object";
import { useBreadcrumb } from "@/components/shell/shell-context";

type Tab = "sources" | "health" | "mappings" | "lineage";
const now = new Date(DEMO_NOW);
const STATUS_TONE: Record<DataSource["status"], Tone> = { Healthy: "ok", Delayed: "warn", Failed: "danger", Paused: "neutral" };
const MAP_TONE: Record<Mapping["status"], Tone> = { Active: "ok", Draft: "neutral", Broken: "danger" };
const DAYS = ["24 Sep", "25 Sep", "26 Sep", "27 Sep", "28 Sep", "29 Sep", "30 Sep"];

/** Data & sources (plan §38 `/app/data`): sources, ingestion health, mappings, lineage. */
export function DataSourcesView() {
  const f = useFormat();
  const router = useRouter();
  const params = useSearchParams();
  const toast = useToast();
  useBreadcrumb(null);
  const q = useSources();
  const sources = q.data ?? [];
  const tab = (["sources", "health", "mappings", "lineage"].includes(params.get("tab") ?? "") ? params.get("tab") : "sources") as Tab;
  const openId = params.get("source");
  const open = sources.find((s) => s.id === openId) ?? null;
  const nav = (qs: string) => router.replace(`/app/data${qs ? `?${qs}` : ""}`, { scroll: false });
  const backRaw = params.get("back");
  const back = backRaw && backRaw.startsWith("/app/data") ? backRaw : null;

  const prov = PORTFOLIO_METRICS[0].provenance;
  const kpis: Metric[] = [
    { id: "healthy", label: "Healthy sources", value: sources.filter((s) => s.status === "Healthy").length, format: "count", comparison: `of ${sources.length} connected`, provenance: { ...prov, formula: "count(status = Healthy)", inputs: [] } },
    { id: "failed", label: "Failing or delayed", value: sources.filter((s) => s.status !== "Healthy").length, format: "count", comparison: "past freshness target", upIsGood: false, provenance: { ...prov, formula: "count(status ∈ {Delayed, Failed})", inputs: sources.filter((s) => s.status !== "Healthy").map((s) => ({ label: s.name, value: s.status })) } },
    { id: "records", label: "Records today", value: sources.reduce((n, s) => n + s.records, 0), format: "count", comparison: "across all sources", provenance: { ...prov, formula: "Σ records ingested", inputs: [] } },
    { id: "errors", label: "Row errors", value: sources.reduce((n, s) => n + s.errors, 0), format: "count", comparison: "quarantined, not loaded", upIsGood: false, provenance: { ...prov, formula: "Σ rejected rows", inputs: sources.filter((s) => s.errors).map((s) => ({ label: s.name, value: String(s.errors) })) } },
  ];

  const srcCols: Column<DataSource>[] = [
    { id: "name", header: "Source", width: 220, hideable: false, value: (s) => s.name, cell: (s) => <span className="font-medium text-ink">{s.name}</span> },
    { id: "kind", header: "Type", value: (s) => s.kind, facet: true, groupable: true },
    { id: "status", header: "Status", value: (s) => s.status, facet: true, cell: (s) => <StatusCell tone={STATUS_TONE[s.status]}>{s.status}</StatusCell> },
    { id: "sync", header: "Last sync", value: (s) => s.lastSync, align: "right", cell: (s) => f.ago(s.lastSync, now) },
    { id: "target", header: "Freshness target", value: (s) => s.freshnessTarget },
    { id: "records", header: "Records", value: (s) => s.records, align: "right", cell: (s) => <NumericCell value={s.records} kind="number" muted /> },
    { id: "mappings", header: "Mappings", value: (s) => s.mappings, align: "right" },
    { id: "errors", header: "Errors", value: (s) => s.errors, align: "right", cell: (s) => <span className={s.errors ? "font-medium text-danger" : "text-ink-4"}>{s.errors}</span> },
    { id: "history", header: "7-day success", value: (s) => s.history[6], sortable: false, cell: (s) => <SparklineCell values={s.history} /> },
    { id: "owner", header: "Owner", value: (s) => s.owner, facet: true, defaultHidden: true },
  ];

  const mapCols: Column<Mapping>[] = [
    { id: "id", header: "Mapping", width: 110, value: (m) => m.id, cell: (m) => <span className="font-data text-[12px] font-medium">{m.id}</span> },
    { id: "source", header: "Source", value: (m) => m.source, facet: true },
    { id: "field", header: "Source field", value: (m) => m.sourceField, cell: (m) => <span className="font-data text-[12px]">{m.sourceField}</span> },
    { id: "target", header: "IBOR target", value: (m) => m.target, cell: (m) => <span className="font-data text-[12px]">{m.target}</span> },
    { id: "transform", header: "Transformation", value: (m) => m.transform },
    { id: "status", header: "Status", value: (m) => m.status, facet: true, cell: (m) => <StatusCell tone={MAP_TONE[m.status]}>{m.status}</StatusCell> },
    { id: "updated", header: "Updated", value: (m) => m.updatedAt, align: "right", cell: (m) => f.ago(m.updatedAt, now) },
  ];

  const ok = DAYS.map((_, i) => sources.reduce((n, s) => n + Math.round((s.records / 7) * (s.history[i] / 100)) / 1000, 0));
  const failed = DAYS.map((_, i) => sources.reduce((n, s) => n + Math.round((s.records / 7) * ((100 - s.history[i]) / 100)) / 1000, 0));

  return (
    <>
      <PageHeader
        variant="list"
        eyebrow="System"
        title="Data & Sources"
        description="See where OCTO numbers come from: connected sources, ingestion health, mappings into the IBOR, and lineage to every metric."
        meta={<FreshnessBadge state="demo" asOf={`checked ${f.time(DEMO_NOW)}`} />}
        tabs={<Tabs<Tab> label="Data sections" value={tab} onChange={(t) => nav(t === "sources" ? "" : `tab=${t}`)} variant="pill" className="pb-3" items={[{ value: "sources", label: "Sources", count: sources.length }, { value: "health", label: "Ingestion health" }, { value: "mappings", label: "Mappings", count: MAPPINGS.length }, { value: "lineage", label: "Lineage" }]} />}
      />
      <PageBody className="space-y-6">
        {tab === "sources" && (
          <>
            <MetricGrid cols={4}>
              {kpis.map((m) => (
                <MetricCard key={m.id} metric={m} variant="compact" state={q.isLoading ? "loading" : "ready"} />
              ))}
            </MetricGrid>
            {sources.some((s) => s.status === "Failed") && <InlineAlert tone="danger" title="News & filings feed is failing">Alert rules that depend on news (e.g. leadership changes) will not fire until the feed recovers.</InlineAlert>}
            <DataTable id="sources" label="Data sources" data={sources} status={q.isLoading ? "loading" : "ready"} columns={srcCols} rowId={(s) => s.id} demo activeRowId={openId} onRowOpen={(s) => nav(`source=${s.id}`)} empty={{ title: "No sources connected", body: "Connect an administrator, custodian, bank or market-data feed to start building the IBOR." }} />
          </>
        )}

        {tab === "health" && (
          <ChartShell
            title="Ingestion volume"
            subtitle="Records per day, thousands — loaded vs rejected"
            legend={<Legend items={[{ label: "Loaded", color: "var(--color-chart-2)" }, { label: "Rejected / late", color: "var(--color-loss)" }]} />}
            source="Ingestion monitor"
            exportData={{ filename: "ingestion", head: ["Day", "Loaded (k)", "Rejected (k)"], rows: DAYS.map((d, i) => [d, ok[i].toFixed(1), failed[i].toFixed(1)]) }}
            height={300}
          >
            <BarChart stacked x={DAYS} series={[{ id: "ok", label: "Loaded", values: ok, color: "var(--color-chart-2)" }, { id: "bad", label: "Rejected / late", values: failed, color: "var(--color-loss)" }]} format={(v) => `${v.toFixed(0)}k`} label="Ingestion volume by day" />
          </ChartShell>
        )}

        {back && tab !== "lineage" && (
          <InlineAlert tone="info" title="Opened from lineage" action={<LinkButton size="sm" href={back}><ArrowLeft /> Back to lineage</LinkButton>}>
            Your lineage path is kept; return to it at any time.
          </InlineAlert>
        )}
        {tab === "mappings" && <DataTable id="mappings" label="Mappings" data={MAPPINGS} columns={mapCols} rowId={(m) => m.id} demo empty={{ title: "No mappings", body: "" }} />}

        {tab === "lineage" && <LineageExplorer />}
      </PageBody>

      <Sheet
        open={!!open}
        onClose={() => (back ? router.replace(back, { scroll: false }) : nav(""))}
        eyebrow={open ? `Source · ${open.kind}` : ""}
        title={open?.name ?? ""}
        footer={back ? <LinkButton href={back}><ArrowLeft /> Back to lineage</LinkButton> : undefined}
      >
        {open && (
          <div className="space-y-5">
            <div className="flex items-center gap-2">
              <StatusBadge tone={STATUS_TONE[open.status]}>{open.status}</StatusBadge>
              <span className="text-[12px] text-ink-3">Last sync {f.ago(open.lastSync, now)}</span>
            </div>
            <ObjectMetadata items={[{ label: "Freshness target", value: open.freshnessTarget }, { label: "Records", value: f.num(open.records) }, { label: "Mappings", value: open.mappings }, { label: "Row errors", value: open.errors }, { label: "Owner", value: open.owner }, { label: "Last sync", value: `${f.dateTime(open.lastSync)} UTC` }]} />
            <div>
              <p className="text-[12px] font-medium text-ink-3">Mappings from this source</p>
              <ul className="mt-2 space-y-1">
                {MAPPINGS.filter((m) => m.source === open.name).map((m) => (
                  <li key={m.id} className="flex items-center justify-between rounded-md border border-line px-2.5 py-1.5 text-[12px]">
                    <span className="font-data">{m.id} → {m.target}</span>
                    <StatusBadge tone={MAP_TONE[m.status]}>{m.status}</StatusBadge>
                  </li>
                ))}
                {!MAPPINGS.some((m) => m.source === open.name) && <li className="text-[12px] text-ink-3">No mappings listed.</li>}
              </ul>
            </div>
            <Button onClick={() => toast({ tone: "info", title: `Re-sync started · ${open.name}`, body: "Runs in the background (demo)." })}>
              <RefreshCw /> Re-sync now
            </Button>
          </div>
        )}
      </Sheet>
    </>
  );
}
