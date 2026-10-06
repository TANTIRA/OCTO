"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ArrowRight, GitBranch } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { AS_OF, FUNDS, NAV_SERIES, PORTFOLIO, QUARTERS, fundDpi, fundTvpi, type Metric } from "@/lib/demo";
import { Sheet } from "@/components/ui/overlay";
import { Button, LinkButton } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/badge";
import { TrendChart } from "@/components/chart/line-chart";
import { NAV_ITEMS } from "@/components/shell/nav-config";
import { useMetricValue } from "./metric-card";
import { TrendBadge } from "./delta";

/** Plain-language definitions; the formula itself comes from the metric's provenance. */
const DEFINITION: Record<string, string> = {
  nav: "Fair value of every position plus fund cash, at the latest quarter-end valuation.",
  irr: "Annualised return to LPs after fees and carry, from dated cash flows and closing NAV.",
  tvpi: "Total value — distributions plus remaining NAV — for every dollar LPs have paid in.",
  dpi: "Cash actually returned to LPs for every dollar paid in.",
  invested: "Cost basis of every active position across all funds.",
  dry: "Commitments not yet called, available for new and follow-on investments.",
  committed: "Capital LPs have committed to the fund.",
  called: "Capital called from LPs and paid in.",
  dist: "Cash distributed to LPs to date.",
};

const RELATED: Record<string, { label: string; href: string }[]> = {
  nav: [
    { label: "Review valuation exceptions", href: "/app/workflows?tab=exceptions" },
    { label: "Open NAV reconciliation", href: "/app/reconciliation" },
  ],
  irr: [{ label: "Compare fund performance", href: "/app/funds" }],
  tvpi: [{ label: "Compare fund performance", href: "/app/funds" }],
  dpi: [{ label: "See distributions by fund", href: "/app/funds" }],
  invested: [{ label: "Open investments", href: "/app/investments" }],
  dry: [{ label: "Review pipeline", href: "/app/deals" }],
};

type Row = { label: string; value: string; sub?: string };

/** An action the current page performs for a metric, e.g. sorting its own table by it. */
export type PageAction = { label: string; run: () => void };

const pathOf = (href: string) => href.split(/[?#]/)[0];
const pageName = (href: string) => NAV_ITEMS.find((n) => n.href === pathOf(href))?.label;

/**
 * The footer's primary action, in order: an in-page action from the page;
 * else the metric's own page; else the page default — but never a link to the
 * page already open, which would only close the drawer and look broken.
 */
function usePrimaryAction(m: Metric, primary: { label: string; href: string }, pageAction?: (m: Metric) => PageAction | null): { label: string; href?: string; run?: () => void } | null {
  const current = usePathname();
  const action = pageAction?.(m);
  if (action) return { label: action.label, run: action.run };
  for (const href of [m.href, primary.href]) {
    if (!href || pathOf(href) === current) continue;
    const name = pageName(href);
    return { label: href === primary.href ? primary.label : name ? `Go to ${name}` : primary.label, href };
  }
  return null;
}

/**
 * Metric-specific breakdown (V2 KPI-003…008): what a reader needs beyond the
 * headline — quarter change and history for NAV, benchmark for IRR, fund
 * contribution for TVPI, distributions for DPI, deployment for invested
 * capital and capacity for dry powder. Every figure derives from demo data.
 */
function useBreakdown(m: Metric): { title: string; rows: Row[]; bars?: { label: string; value: number; display: string }[] } | null {
  const f = useFormat();
  const P = PORTFOLIO;
  const last = NAV_SERIES[NAV_SERIES.length - 1];
  const prev = NAV_SERIES[NAV_SERIES.length - 2];
  switch (m.id) {
    case "nav":
      return {
        title: "Quarter change",
        rows: [
          { label: "Current NAV", value: f.money(P.nav) },
          { label: "Change vs Q2 2026", value: `${f.delta(((last.nav - prev.nav) / prev.nav) * 100)} · ${f.delta((last.nav - prev.nav) * 1e6, "$")}` },
          { label: "Three-year change", value: f.delta(((last.nav - NAV_SERIES[0].nav) / NAV_SERIES[0].nav) * 100), sub: `since ${NAV_SERIES[0].q}` },
          { label: "Freshness", value: "Q3 marks · struck 13:24 UTC" },
        ],
      };
    case "irr":
      return {
        title: "Return context",
        rows: [
          { label: "Net IRR, since inception", value: f.pct(P.netIrr) },
          { label: "Gross IRR", value: f.pct(P.grossIrr), sub: "before fees and carry" },
          { label: "Hurdle", value: f.pct(8), sub: "preferred return per LPA" },
          { label: "Public benchmark (PME)", value: f.pct(11.4), sub: "MSCI AC Asia, KS-PME, demo" },
          { label: "Largest driver", value: "Flagship II marks", sub: "+0.6 pts this quarter" },
        ],
      };
    case "tvpi":
      return {
        title: "Fund contribution",
        rows: [
          { label: "Portfolio TVPI", value: f.multiple(P.tvpi) },
          { label: "Total value", value: f.money(P.distributions + P.nav), sub: "distributions + NAV" },
          { label: "Paid-in", value: f.money(P.called) },
        ],
        bars: FUNDS.map((x) => ({ label: x.short, value: ((x.distributions + x.nav) / (P.distributions + P.nav)) * 100, display: `${f.multiple(fundTvpi(x))} · ${f.pct(((x.distributions + x.nav) / (P.distributions + P.nav)) * 100, 0)} of value` })),
      };
    case "dpi":
      return {
        title: "Distributions",
        rows: [
          { label: "Portfolio DPI", value: f.multiple(P.dpi) },
          { label: "Distributed to LPs", value: f.money(P.distributions) },
          { label: "Paid-in", value: f.money(P.called) },
          { label: "Distributed this quarter", value: f.money(31.8e6), sub: "Q3 2026" },
        ],
        bars: FUNDS.filter((x) => x.distributions > 0).map((x) => ({ label: x.short, value: (x.distributions / P.distributions) * 100, display: `${f.money(x.distributions)} · DPI ${f.multiple(fundDpi(x))}` })),
      };
    case "invested":
      return {
        title: "Deployment",
        rows: [
          { label: "Invested (cost)", value: f.money(P.invested) },
          { label: "Called from LPs", value: f.money(P.called) },
          { label: "Remaining commitments", value: f.money(P.committed - P.called) },
          { label: "Deployment", value: f.pct((P.called / P.committed) * 100, 0), sub: "called ÷ committed" },
        ],
      };
    case "dry":
      return {
        title: "Capacity",
        rows: [
          { label: "Available", value: f.money(P.dryPowder) },
          { label: "Committed", value: f.money(P.committed) },
          { label: "Deployed (called)", value: f.money(P.called) },
          { label: "Capacity left", value: f.pct((P.dryPowder / P.committed) * 100, 0), sub: "of commitments" },
        ],
      };
    default:
      return null;
  }
}

/**
 * KPI metric drawer (KPI-002). Opened by clicking a whole KPI card: value and
 * change, plain-language definition, period, trend, drivers, source/lineage
 * summary and related actions. "View lineage" hands over to the full lineage
 * drawer; the card itself no longer carries a Lineage link.
 */
export function KpiMetricDrawer({
  metric,
  onClose,
  onLineage,
  primary = { label: "View portfolio", href: "/app/portfolio" },
  pageAction,
}: {
  metric: Metric | null;
  onClose: () => void;
  onLineage?: (m: Metric) => void;
  primary?: { label: string; href: string };
  /** What this page can do with the metric in place; wins over navigating away. */
  pageAction?: (m: Metric) => PageAction | null;
}) {
  if (!metric) return null;
  return <DrawerBody metric={metric} onClose={onClose} onLineage={onLineage} primary={primary} pageAction={pageAction} />;
}

function DrawerBody({ metric, onClose, onLineage, primary, pageAction }: { metric: Metric; onClose: () => void; onLineage?: (m: Metric) => void; primary: { label: string; href: string }; pageAction?: (m: Metric) => PageAction | null }) {
  const f = useFormat();
  const fmt = useMetricValue();
  const breakdown = useBreakdown(metric);
  const action = usePrimaryAction(metric, primary, pageAction);
  const current = usePathname();
  // Related links to the page already open (or to the primary target) would be dead ends or duplicates.
  const related = (RELATED[metric.id] ?? []).filter((r) => pathOf(r.href) !== current && r.href !== action?.href);
  const m = metric;
  const p = m.provenance;
  const x = m.spark ? QUARTERS.slice(-m.spark.length) : [];
  const valueOf = (v: number) => fmt({ value: m.format === "money" ? v * 1e6 : v, format: m.format });

  return (
    <Sheet
      open
      onClose={onClose}
      eyebrow="Metric"
      title={m.label}
      footer={
        <div className="flex flex-wrap items-center justify-end gap-2">
          {onLineage && (
            <Button onClick={() => onLineage(m)}>
              <GitBranch /> View lineage
            </Button>
          )}
          {action?.href && (
            <LinkButton href={action.href} variant="primary" onClick={onClose}>
              {action.label} <ArrowRight />
            </LinkButton>
          )}
          {action?.run && (
            <Button
              variant="primary"
              onClick={() => {
                action.run!();
                onClose();
              }}
            >
              {action.label} <ArrowRight />
            </Button>
          )}
          {!action && <Button onClick={onClose}>Close</Button>}
        </div>
      }
    >
      <div className="space-y-6">
        <section aria-label="Current value">
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <p className="text-kpi-xl font-semibold tabular-nums text-ink">{fmt(m)}</p>
            {m.delta !== undefined && <TrendBadge value={m.delta} unit={m.deltaUnit} upIsGood={m.upIsGood} trend={m.trend} />}
            <span className="text-[12px] text-ink-3">{m.comparison}</span>
          </div>
          <div className="mt-2 flex flex-wrap gap-2">
            <StatusBadge tone="info">Demo data</StatusBadge>
            <StatusBadge tone="neutral" dot={false}>
              Q3 2026 · as of {f.date(AS_OF)}
            </StatusBadge>
          </div>
        </section>

        {breakdown && (
          <Section title={breakdown.title}>
            <dl className="divide-y divide-line-subtle rounded-md border border-line">
              {breakdown.rows.map((r) => (
                <div key={r.label} className="flex min-h-10 items-center justify-between gap-3 px-3 py-2 text-[13px]">
                  <dt className="min-w-0">
                    <span className="block text-ink-2">{r.label}</span>
                    {r.sub && <span className="block text-[11px] text-ink-4">{r.sub}</span>}
                  </dt>
                  <dd className="shrink-0 text-right font-medium tabular-nums text-ink">{r.value}</dd>
                </div>
              ))}
            </dl>
            {breakdown.bars && (
              <ul className="mt-3 space-y-2" aria-label={`${breakdown.title} by fund`}>
                {breakdown.bars.map((b) => (
                  <li key={b.label}>
                    <div className="flex items-baseline justify-between gap-3 text-[12px]">
                      <span className="font-medium text-ink-2">{b.label}</span>
                      <span className="tabular-nums text-ink-3">{b.display}</span>
                    </div>
                    <div aria-hidden className="mt-1 h-1.5 overflow-hidden rounded-full bg-sunken">
                      <div className="h-full rounded-full bg-accent" style={{ width: `${Math.max(2, b.value)}%` }} />
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Section>
        )}

        <Section title="Definition">
          <p className="text-[13px] text-ink-2">{DEFINITION[m.id] ?? p.transformation}</p>
          <code className="mt-2 block rounded-sm border border-line bg-subtle px-3 py-2 font-data text-[12px] text-ink">{p.formula}</code>
        </Section>

        {m.spark && (
          <Section title="Supporting trend" hint={`Last ${m.spark.length} quarters`}>
            <div className="h-40">
              <TrendChart x={x} series={[{ id: m.id, label: m.label, values: m.spark }]} format={valueOf} label={`${m.label}, last ${m.spark.length} quarters`} />
            </div>
          </Section>
        )}

        <Section title="Drivers">
          <ul className="divide-y divide-line-subtle rounded-md border border-line">
            {p.inputs.map((i) => (
              <li key={i.label} className="flex min-h-10 items-center justify-between gap-3 px-3 py-2 text-[13px]">
                {i.href ? (
                  <Link href={i.href} onClick={onClose} className="inline-flex min-w-0 items-center gap-1 text-ink-2 hover:text-accent-ink">
                    <span className="truncate">{i.label}</span> <ArrowRight aria-hidden className="size-3 shrink-0" />
                  </Link>
                ) : (
                  <span className="min-w-0 truncate text-ink-2">{i.label}</span>
                )}
                <span className="shrink-0 font-medium tabular-nums text-ink">{i.value}</span>
              </li>
            ))}
          </ul>
        </Section>

        <Section title="Source and freshness">
          <dl className="grid grid-cols-2 gap-3 text-[13px]">
            {[
              ["System", p.sourceSystem],
              ["Document", p.sourceDocument ?? "—"],
              ["As of", `${f.dateTime(p.asOf)} UTC`],
              ["Definition", p.version],
            ].map(([k, v]) => (
              <div key={k} className="min-w-0">
                <dt className="text-[12px] text-ink-3">{k}</dt>
                <dd className="mt-0.5 break-words text-ink-2">{v}</dd>
              </div>
            ))}
          </dl>
        </Section>

        {related.length > 0 && (
          <Section title="Related actions">
            <ul className="space-y-1">
              {related.map((r) => (
                <li key={r.href + r.label}>
                  <Link href={r.href} onClick={onClose} className="flex h-9 items-center justify-between rounded-sm px-2 text-[13px] text-ink-2 hover:bg-hover hover:text-ink">
                    {r.label} <ArrowRight aria-hidden className="size-3.5 text-ink-4" />
                  </Link>
                </li>
              ))}
            </ul>
          </Section>
        )}
      </div>
    </Sheet>
  );
}

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section aria-label={title}>
      <div className="mb-2 flex items-baseline justify-between gap-2">
        <h3 className="text-[13px] font-semibold text-ink">{title}</h3>
        {hint && <span className="text-[11px] text-ink-4">{hint}</span>}
      </div>
      {children}
    </section>
  );
}

/** V2 shared-primitive name for the KPI drawer. */
export { KpiMetricDrawer as MetricDrawer };
