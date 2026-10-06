/**
 * Metric definitions with lineage (plan §24). Values are derived from the demo
 * entities, so the KPI cards, provenance drawer and tables agree.
 */

import { ALERTS, APPROVALS, EXCEPTIONS, RECON, TASKS } from "./ops";
import { FUNDS, INVESTMENTS, PORTFOLIO, at, fundDpi, fundDryPowder, fundTvpi, type Fund } from "./entities";

export type MetricFormat = "money" | "pct" | "multiple" | "count";

export type Provenance = {
  formula: string;
  inputs: { label: string; value: string; href?: string }[];
  asOf: string;
  sourceSystem: string;
  sourceDocument?: string;
  transformation: string;
  version: string;
  ai?: { confidence: "High" | "Medium" | "Low"; citations: string[]; verification: string; reviewer?: string };
};

export type Metric = {
  id: string;
  label: string;
  value: number;
  format: MetricFormat;
  delta?: number;
  deltaUnit?: "%" | "pts" | "×" | "$" | "";
  comparison: string;
  /** Whether an increase is good. Undefined = neutral (e.g. counts of work). */
  upIsGood?: boolean;
  /** Explicit business meaning of this period's change; wins over sign inference (V2 CC-003). */
  trend?: "positive" | "negative" | "neutral";
  spark?: number[];
  href?: string;
  provenance: Provenance;
};

const m = (v: number) => `$${(v / 1e6).toFixed(1)}M`;
const NAV_AT = at(0.3);

const unrealized = INVESTMENTS.reduce((n, i) => n + i.fairValue, 0);
const realized = PORTFOLIO.distributions;
const cost = PORTFOLIO.invested;

export const PORTFOLIO_METRICS: Metric[] = [
  {
    id: "nav",
    trend: "positive",
    label: "Total NAV",
    value: PORTFOLIO.nav,
    format: "money",
    delta: 3.2,
    deltaUnit: "%",
    comparison: "vs Q2 2026",
    upIsGood: true,
    spark: [742, 751, 760, 772, 779, 790, 801, 812.4],
    href: "/app/portfolio",
    provenance: {
      formula: "Σ fund NAV",
      inputs: FUNDS.map((f) => ({ label: f.name, value: m(f.nav), href: `/app/funds/${f.slug}` })),
      asOf: NAV_AT,
      sourceSystem: "IBOR · position ledger + Q3 valuations",
      sourceDocument: "Q3 2026 valuation pack (VAL-2291)",
      transformation: "Positions derived from the transaction ledger × approved marks, FX at WM/R close",
      version: "NAV definition v2.1",
    },
  },
  {
    id: "irr",
    trend: "negative",
    label: "Net IRR",
    value: PORTFOLIO.netIrr,
    format: "pct",
    delta: -0.4,
    deltaUnit: "pts",
    comparison: "vs Q2 2026",
    upIsGood: true,
    spark: [19.1, 19.0, 18.9, 18.8, 18.7, 18.6, 18.6, 18.2],
    href: "/app/analytics",
    provenance: {
      formula: "XIRR(net LP cash flows, closing NAV)",
      inputs: [
        { label: "LP cash flows", value: "214 flows, 2019–2026" },
        { label: "Closing NAV", value: m(PORTFOLIO.nav) },
      ],
      asOf: NAV_AT,
      sourceSystem: "IBOR · cash-flow ledger",
      transformation: "Daily XIRR on net-of-fee flows; NAV treated as terminal flow",
      version: "Net IRR v3.2",
    },
  },
  {
    id: "tvpi",
    trend: "positive",
    label: "TVPI",
    value: PORTFOLIO.tvpi,
    format: "multiple",
    delta: 0.05,
    deltaUnit: "×",
    comparison: "vs Q2 2026",
    upIsGood: true,
    spark: [1.52, 1.54, 1.56, 1.58, 1.59, 1.61, 1.62, 1.64],
    provenance: {
      formula: "(Distributions + NAV) ÷ Paid-in",
      inputs: [
        { label: "Distributions", value: m(PORTFOLIO.distributions) },
        { label: "NAV", value: m(PORTFOLIO.nav) },
        { label: "Paid-in", value: m(PORTFOLIO.called) },
      ],
      asOf: NAV_AT,
      sourceSystem: "IBOR",
      transformation: "Fund-level sums, USD",
      version: "TVPI v2.0",
    },
  },
  {
    id: "dpi",
    trend: "positive",
    label: "DPI",
    value: PORTFOLIO.dpi,
    format: "multiple",
    delta: 0.02,
    deltaUnit: "×",
    comparison: "vs Q2 2026",
    upIsGood: true,
    spark: [0.29, 0.31, 0.32, 0.33, 0.35, 0.36, 0.36, 0.38],
    provenance: {
      formula: "Distributions ÷ Paid-in",
      inputs: [
        { label: "Distributions", value: m(PORTFOLIO.distributions) },
        { label: "Paid-in", value: m(PORTFOLIO.called) },
      ],
      asOf: NAV_AT,
      sourceSystem: "IBOR",
      transformation: "Fund-level sums, USD",
      version: "DPI v2.0",
    },
  },
  {
    id: "invested",
    trend: "positive",
    label: "Invested capital",
    value: cost,
    format: "money",
    delta: 42e6,
    deltaUnit: "$",
    comparison: "this quarter",
    spark: [522, 536, 553, 567, 585, 602, 603, cost / 1e6],
    href: "/app/investments",
    provenance: {
      formula: "Σ position cost",
      inputs: [{ label: "Positions", value: `${INVESTMENTS.length} across ${FUNDS.length} funds` }],
      asOf: NAV_AT,
      sourceSystem: "IBOR · transaction ledger",
      transformation: "Cost basis, FIFO",
      version: "Cost v1.3",
    },
  },
  {
    id: "dry",
    trend: "negative",
    label: "Dry powder",
    value: PORTFOLIO.dryPowder,
    format: "money",
    delta: -42e6,
    deltaUnit: "$",
    comparison: "this quarter",
    spark: [498, 481, 470, 462, 451, 439, 447, PORTFOLIO.dryPowder / 1e6],
    href: "/app/funds",
    provenance: {
      formula: "Σ (commitments − called capital)",
      inputs: FUNDS.map((f) => ({ label: f.short, value: m(fundDryPowder(f)) })),
      asOf: NAV_AT,
      sourceSystem: "IBOR · LP commitments",
      transformation: "Undrawn commitment, recallable distributions excluded",
      version: "Dry powder v1.0",
    },
  },
];

export const SECONDARY_METRICS = {
  unrealized,
  realized,
  moic: (unrealized + INVESTMENTS.reduce((n, i) => n + i.realized, 0)) / cost,
  grossIrr: PORTFOLIO.grossIrr,
  cash: PORTFOLIO.cash,
};

export const OPS_COUNTS = {
  openAlerts: ALERTS.filter((a) => a.state === "Open").length,
  critical: ALERTS.filter((a) => a.severity === "critical" && a.state !== "Resolved").length,
  recon: RECON.filter((r) => r.state !== "Resolved").length,
  approvals: APPROVALS.length,
  tasks: TASKS.filter((t) => t.assignee === "You" && t.status !== "Done").length,
  exceptions: EXCEPTIONS.length,
};

/** Fund metrics with lineage for fund pages. */
export function fundMetrics(f: Fund): Metric[] {
  const base = (id: string, label: string, value: number, format: MetricFormat, formula: string, inputs: Provenance["inputs"]): Metric => ({
    id,
    label,
    value,
    format,
    comparison: "as of 30 Sep 2026",
    provenance: { formula, inputs, asOf: NAV_AT, sourceSystem: "IBOR", transformation: "Fund-level sums, USD", version: "Fund metrics v2.0" },
  });
  return [
    base("committed", "Committed", f.committed, "money", "Σ LP commitments", [{ label: "LP commitments", value: m(f.committed) }]),
    base("called", "Called", f.called, "money", "Σ capital calls paid", [{ label: "Capital calls", value: m(f.called) }]),
    { ...base("nav", "NAV", f.nav, "money", "Σ position fair value + cash", [{ label: "Positions", value: m(f.nav - f.cash) }, { label: "Cash", value: m(f.cash) }]), delta: 2.8, deltaUnit: "%", comparison: "vs Q2 2026", upIsGood: true },
    base("dist", "Distributions", f.distributions, "money", "Σ distributions paid", [{ label: "Distributions", value: m(f.distributions) }]),
    base("tvpi", "TVPI", fundTvpi(f), "multiple", "(Distributions + NAV) ÷ Paid-in", [{ label: "Distributions", value: m(f.distributions) }, { label: "NAV", value: m(f.nav) }, { label: "Paid-in", value: m(f.called) }]),
    base("dpi", "DPI", fundDpi(f), "multiple", "Distributions ÷ Paid-in", [{ label: "Distributions", value: m(f.distributions) }, { label: "Paid-in", value: m(f.called) }]),
    { ...base("irr", "Net IRR", f.netIrr, "pct", "XIRR(net LP cash flows, NAV)", [{ label: "Closing NAV", value: m(f.nav) }]), upIsGood: true },
    base("dry", "Dry powder", fundDryPowder(f), "money", "Commitments − called", [{ label: "Undrawn", value: m(fundDryPowder(f)) }]),
  ];
}
