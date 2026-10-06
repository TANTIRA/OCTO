/**
 * Demo back-office objects: data sources, mappings, lineage, users,
 * roles, and audit events. Illustrative only.
 */

import { at, daysAgo } from "./entities";

/* ---------- Data sources ---------- */

export type SourceStatus = "Healthy" | "Delayed" | "Failed" | "Paused";

export type DataSource = {
  id: string;
  name: string;
  kind: "Fund administrator" | "Custodian" | "Bank" | "Market data" | "CRM" | "Documents" | "News";
  status: SourceStatus;
  lastSync: string;
  freshnessTarget: string;
  records: number;
  mappings: number;
  errors: number;
  owner: string;
  history: number[];
};

export const SOURCES: DataSource[] = [
  { id: "SRC-ADM1", name: "Apex Fund Services", kind: "Fund administrator", status: "Healthy", lastSync: at(11), freshnessTarget: "Daily by 08:00", records: 18420, mappings: 42, errors: 0, owner: "Data ops", history: [100, 100, 100, 98, 100, 100, 100] },
  { id: "SRC-ADM2", name: "Harbour Administration", kind: "Fund administrator", status: "Delayed", lastSync: at(52), freshnessTarget: "Daily by 08:00", records: 6210, mappings: 28, errors: 3, owner: "Data ops", history: [100, 96, 100, 100, 88, 72, 64] },
  { id: "SRC-CUS", name: "Straits Custody", kind: "Custodian", status: "Healthy", lastSync: at(4), freshnessTarget: "Hourly", records: 9140, mappings: 19, errors: 0, owner: "Data ops", history: [100, 100, 100, 100, 100, 99, 100] },
  { id: "SRC-BNK", name: "Operating bank statements", kind: "Bank", status: "Healthy", lastSync: at(2), freshnessTarget: "Intraday", records: 22100, mappings: 11, errors: 0, owner: "Treasury", history: [100, 100, 100, 100, 100, 100, 100] },
  { id: "SRC-MKT", name: "Market data feed", kind: "Market data", status: "Delayed", lastSync: at(20), freshnessTarget: "15 min", records: 510000, mappings: 64, errors: 12, owner: "Data ops", history: [100, 100, 97, 94, 100, 91, 86] },
  { id: "SRC-CRM", name: "Deal CRM", kind: "CRM", status: "Healthy", lastSync: at(1), freshnessTarget: "15 min", records: 3480, mappings: 23, errors: 0, owner: "Deal team", history: [100, 100, 100, 100, 100, 100, 100] },
  { id: "SRC-DOC", name: "Document vault", kind: "Documents", status: "Healthy", lastSync: at(0.5), freshnessTarget: "On upload", records: 12900, mappings: 8, errors: 1, owner: "Legal ops", history: [100, 100, 100, 100, 99, 100, 100] },
  { id: "SRC-NEWS", name: "News & filings", kind: "News", status: "Failed", lastSync: at(30), freshnessTarget: "Hourly", records: 88000, mappings: 5, errors: 41, owner: "Data ops", history: [100, 100, 100, 92, 60, 20, 0] },
];

export type Mapping = { id: string; source: string; sourceField: string; target: string; transform: string; status: "Active" | "Draft" | "Broken"; updatedAt: string };

export const MAPPINGS: Mapping[] = [
  { id: "MAP-114", source: "Apex Fund Services", sourceField: "cash_balance.usd", target: "ibor.cash(account=USD-OP)", transform: "sum by account; FX at close", status: "Active", updatedAt: daysAgo(40) },
  { id: "MAP-031", source: "Apex Fund Services", sourceField: "holdings.quantity", target: "ibor.position.quantity", transform: "identity; corporate actions applied", status: "Active", updatedAt: daysAgo(120) },
  { id: "MAP-090", source: "Harbour Administration", sourceField: "accruals.mgmt_fee", target: "ibor.accrual(type=MGMT_FEE)", transform: "ACT/365 day count", status: "Broken", updatedAt: daysAgo(5) },
  { id: "MAP-207", source: "Straits Custody", sourceField: "confirms.trade", target: "ibor.trade", transform: "match on ISIN + trade date", status: "Active", updatedAt: daysAgo(60) },
  { id: "MAP-012", source: "Market data feed", sourceField: "fx.close", target: "ibor.fx_rate", transform: "WM/R 16:00 London", status: "Active", updatedAt: daysAgo(200) },
  { id: "MAP-150", source: "Operating bank statements", sourceField: "statement.line", target: "ibor.cash_event", transform: "rule-based categorisation", status: "Active", updatedAt: daysAgo(30) },
  { id: "MAP-220", source: "Deal CRM", sourceField: "opportunity", target: "ontology.Prospect", transform: "stage map v3", status: "Draft", updatedAt: daysAgo(2) },
];

/** Lineage for the headline NAV: source → mapping → IBOR → metric. */
export const NAV_LINEAGE = [
  { layer: "Sources", nodes: ["Apex Fund Services", "Harbour Administration", "Straits Custody", "Market data feed"] },
  { layer: "Mappings", nodes: ["MAP-114 cash", "MAP-031 positions", "MAP-090 accruals", "MAP-012 FX"] },
  { layer: "IBOR", nodes: ["Transaction ledger", "Positions (derived)", "Cash (derived)", "Valuations"] },
  { layer: "Metrics", nodes: ["Fund NAV v2.1", "Portfolio NAV", "TVPI v2.0", "Net IRR v3.2"] },
];

/* ---------- Lineage graph (V3 LINEAGE-002…006) ---------- */

export type LineageNodeType = "source" | "mapping" | "ibor" | "metric";
export type LineageNode = { id: string; type: LineageNodeType; label: string; facts: [string, string][]; flag?: "warn" | "danger"; href?: string; hrefLabel?: string };
export type LineageChain = { id: string; name: string; nodes: [string, string, string, string]; note?: string };

const src = (id: string): LineageNode => {
  const s = SOURCES.find((x) => x.id === id)!;
  return {
    id: s.id,
    type: "source",
    label: s.name,
    facts: [
      ["Provider", `${s.name} · ${s.kind}`],
      ["Last received", s.lastSync],
      ["Rows processed", s.records.toLocaleString("en-US")],
      ["Health", `${s.status}${s.errors ? ` · ${s.errors} row errors` : ""}`],
      ["Freshness target", s.freshnessTarget],
    ],
    flag: s.status === "Failed" ? "danger" : s.status === "Delayed" ? "warn" : undefined,
    href: `/app/data?source=${s.id}`,
    hrefLabel: "Open source",
  };
};
const map = (id: string, label: string, version: string): LineageNode => {
  const m = MAPPINGS.find((x) => x.id === id)!;
  return {
    id: m.id,
    type: "mapping",
    label,
    facts: [
      ["Mapping ID", m.id],
      ["Source field", m.sourceField],
      ["Target field", m.target],
      ["Rule", m.transform],
      ["Version", `${version} · ${m.status}`],
      ["Last changed", m.updatedAt],
    ],
    flag: m.status === "Broken" ? "danger" : m.status === "Draft" ? "warn" : undefined,
    href: "/app/data?tab=mappings",
    hrefLabel: "Open mappings",
  };
};
const ibor = (id: string, label: string, record: string, version: string): LineageNode => ({
  id,
  type: "ibor",
  label,
  facts: [
    ["Object", label],
    ["Record type", record],
    ["Effective date", "30 Sep 2026"],
    ["Version", version],
  ],
});
const metric = (id: string, label: string, definition: string, inputs: string, href: string): LineageNode => ({
  id,
  type: "metric",
  label,
  facts: [
    ["Definition", definition],
    ["Version", label.match(/v\d+(\.\d+)?/)?.[0] ?? "v1.0"],
    ["Inputs", inputs],
    ["Last calculated", "30 Sep 2026, 13:24 UTC"],
  ],
  href,
  hrefLabel: "Open metric",
});

export const LINEAGE_NODES: LineageNode[] = [
  src("SRC-ADM1"),
  src("SRC-ADM2"),
  src("SRC-CUS"),
  src("SRC-BNK"),
  src("SRC-MKT"),
  map("MAP-114", "MAP-114 cash", "v4"),
  map("MAP-031", "MAP-031 positions", "v7"),
  map("MAP-090", "MAP-090 accruals", "v2"),
  map("MAP-207", "MAP-207 trade confirms", "v3"),
  map("MAP-012", "MAP-012 FX", "v5"),
  map("MAP-150", "MAP-150 cash events", "v2"),
  ibor("IB-LEDGER", "Transaction ledger", "Ledger event (append-only)", "Ledger schema v3"),
  ibor("IB-POS", "Positions (derived)", "Derived position, per instrument", "Derivation v2.4"),
  ibor("IB-CASH", "Cash (derived)", "Derived cash balance, per account", "Derivation v2.4"),
  ibor("IB-VAL", "Valuations", "Approved mark, per position", "Valuation policy v4"),
  metric("M-FNAV", "Fund NAV v2.1", "Σ position fair value + cash − accruals, per fund", "Positions, cash, accruals, FX", "/app/funds/fnd-002"),
  metric("M-PNAV", "Portfolio NAV", "Σ fund NAV across all vehicles", "Fund NAV × 5 funds", "/app/portfolio"),
  metric("M-TVPI", "TVPI v2.0", "(Distributions + NAV) ÷ paid-in", "Distributions, NAV, paid-in", "/app/analytics"),
  metric("M-IRR", "Net IRR v3.2", "XIRR of dated LP cash flows and closing NAV", "LP cash flows, closing NAV", "/app/analytics"),
];

export const LINEAGE_CHAINS: LineageChain[] = [
  { id: "fund-nav-cash", name: "Fund NAV · cash", nodes: ["SRC-ADM1", "MAP-114", "IB-LEDGER", "M-FNAV"] },
  { id: "tvpi-positions", name: "TVPI · positions", nodes: ["SRC-ADM1", "MAP-031", "IB-POS", "M-TVPI"] },
  { id: "fund-nav-accruals", name: "Fund NAV · accruals", nodes: ["SRC-ADM2", "MAP-090", "IB-LEDGER", "M-FNAV"], note: "MAP-090 is broken: management-fee accruals are not loading, so Fund NAV uses the prior accrual." },
  { id: "portfolio-nav-trades", name: "Portfolio NAV · trades", nodes: ["SRC-CUS", "MAP-207", "IB-LEDGER", "M-PNAV"] },
  { id: "portfolio-nav-fx", name: "Portfolio NAV · FX", nodes: ["SRC-MKT", "MAP-012", "IB-VAL", "M-PNAV"], note: "The market data feed is delayed; FX uses the last published close." },
  { id: "irr-cash", name: "Net IRR · cash flows", nodes: ["SRC-BNK", "MAP-150", "IB-CASH", "M-IRR"] },
];

/* ---------- Users, roles, audit ---------- */

export const USERS = [
  { id: "U-01", name: "Andi Wijaya", email: "a.wijaya@octo.example", role: "Partner", workspaces: 3, lastActive: at(1), mfa: true },
  { id: "U-02", name: "Rina Tan", email: "r.tan@octo.example", role: "Investment director", workspaces: 2, lastActive: at(2), mfa: true },
  { id: "U-03", name: "Maya Sari", email: "m.sari@octo.example", role: "Investment director", workspaces: 2, lastActive: at(5), mfa: true },
  { id: "U-04", name: "Daniel Lim", email: "d.lim@octo.example", role: "Associate", workspaces: 2, lastActive: at(8), mfa: false },
  { id: "U-05", name: "Fund accounting", email: "fa@octo.example", role: "Operations", workspaces: 3, lastActive: at(0.5), mfa: true },
  { id: "U-06", name: "Investor relations", email: "ir@octo.example", role: "Operations", workspaces: 3, lastActive: at(3), mfa: true },
];

export const ROLES = [
  { name: "Partner", members: 1, permissions: ["Approve IC", "Approve LP reports", "View all funds", "Manage users"] },
  { name: "Investment director", members: 2, permissions: ["Approve IC (co-sign)", "Edit deals", "View assigned funds"] },
  { name: "Associate", members: 1, permissions: ["Edit deals", "Draft reports", "View assigned funds"] },
  { name: "Operations", members: 2, permissions: ["Resolve recon", "Manage sources", "Draft reports"] },
];

export const AUDIT = [
  { id: "AUD-9921", actor: "M. Sari", action: "Approved", object: "IC memo · Garuda Fibre follow-on", at: at(5), ip: "10.2.4.18" },
  { id: "AUD-9918", actor: "Fund accounting", action: "Resolved (Accept IBOR)", object: "REC-2188", at: at(7), ip: "10.2.4.33" },
  { id: "AUD-9910", actor: "A. Wijaya", action: "Changed role", object: "D. Lim → Associate", at: at(26), ip: "10.2.4.11" },
  { id: "AUD-9904", actor: "OCTO", action: "Ingested", object: "Apex Fund Services · 30 Sep", at: at(11), ip: "system" },
  { id: "AUD-9899", actor: "R. Tan", action: "Activated rule", object: "RUL-018 v4", at: at(50), ip: "10.2.4.21" },
];
